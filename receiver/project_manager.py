"""
Module for managing projects and conversations in Tether.
"""
from __future__ import annotations

import os
import glob
import json
import urllib.parse
import sqlite3
import re
import time
import uuid

class ProjectManager:
    """
    Encapsulates project management logic, separating it from the main receiver loop.
    """

    def __init__(self, projects_dir: str, conversations_dir: str, brain_dir: str, transcript_scan_lines: int = 30):
        self.projects_dir = projects_dir
        self.conversations_dir = conversations_dir
        self.brain_dir = brain_dir
        self.transcript_scan_lines = transcript_scan_lines

    def get_projects_with_details(self) -> list[dict]:
        """Returns list of {id, name, folderUri, folderName} dicts, sorted by last user chat.
        
        Uses the authoritative conversation DB metadata blobs to map conversations
        to projects (same source of truth as the desktop Rover app), then
        sorts by the most recent USER_INPUT timestamp across all conversations
        for each project.
        """
        project_files = glob.glob(os.path.join(self.projects_dir, "*.json"))

        # Step 1: Load all projects and build a path -> project_name lookup
        projects = []
        path_to_project = {}  # normalized_path -> project_name
        id_to_project = {}    # project_id -> project_name
        for f in project_files:
            try:
                with open(f, 'r', encoding='utf-8') as file:
                    data = json.load(file)
                    name = data.get("name", "")
                    proj_id = data.get("id", "")

                    folder_uri = ""
                    folder_name = ""
                    resources = data.get("projectResources", {}).get("resources", [])
                    if resources:
                        folder_uri = resources[0].get("gitFolder", {}).get("folderUri", "")
                        if not folder_uri:
                            folder_uri = resources[0].get("folderUri", "")
                        if folder_uri:
                            folder_name = folder_uri.rstrip('/').split('/')[-1]

                    if name:
                        projects.append({
                            "id": proj_id,
                            "name": name,
                            "folderUri": folder_uri,
                            "folderName": folder_name,
                            "_json_mtime": os.path.getmtime(f),
                        })
                        # Build lookup for DB matching
                        if proj_id:
                            id_to_project[proj_id] = name
                            
                        if folder_uri:
                            decoded = urllib.parse.unquote(folder_uri)
                            if decoded.startswith('file:///'):
                                decoded = decoded[8:]
                            elif decoded.startswith('file://'):
                                decoded = decoded[7:]
                            path_to_project[decoded.lower().replace('\\', '/').rstrip('/')] = name
            except Exception:
                pass

        # Step 2: Map conversations to projects using DB metadata blobs
        # Each conversation DB has a trajectory_metadata_blob table containing
        # the workspace file:// URI as a protobuf-encoded string.
        conv_to_project = {}  # conv_id -> project_name
        if os.path.isdir(self.conversations_dir):
            for db_file in os.listdir(self.conversations_dir):
                if not db_file.endswith('.db'):
                    continue
                conv_id = db_file[:-3]  # strip .db
                db_path = os.path.join(self.conversations_dir, db_file)
                try:
                    conn = sqlite3.connect(db_path)
                    cursor = conn.cursor()
                    cursor.execute("SELECT data FROM trajectory_metadata_blob WHERE id='main' LIMIT 1")
                    row = cursor.fetchone()
                    conn.close()
                    if row and isinstance(row[0], bytes):
                        # Extract file:// URIs from the protobuf blob
                        uris = re.findall(rb'file:///[a-zA-Z]:/[^\x00-\x1f\x80-\x9f]+', row[0])
                        for uri_bytes in uris:
                            uri_str = uri_bytes.decode('utf-8', errors='replace')
                            # Clean: take path up to first non-path character
                            m = re.match(r'file:///[a-zA-Z]:/[^"\x00-\x1f]*', uri_str)
                            if m:
                                path = m.group(0)[8:]  # strip file:///
                                path = urllib.parse.unquote(path)
                                path_norm = path.lower().replace('\\', '/').rstrip('/')
                                # Match against known project paths (longest match first)
                                best_match = None
                                best_len = 0
                                for proj_path, proj_name in path_to_project.items():
                                    if (path_norm == proj_path or path_norm.startswith(proj_path + '/')) and len(proj_path) > best_len:
                                        best_match = proj_name
                                        best_len = len(proj_path)
                                if best_match:
                                    conv_to_project[conv_id] = best_match
                                    break
                                    
                        # Also extract UUIDs and check if they match any project ID
                        uuids = re.findall(rb'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', row[0])
                        for uuid_bytes in uuids:
                            uuid_str = uuid_bytes.decode('utf-8')
                            if uuid_str in id_to_project:
                                conv_to_project[conv_id] = id_to_project[uuid_str]
                except Exception:
                    continue

        # Step 3: For each project, find the latest USER_INPUT timestamp
        # across all its conversations
        project_last_user = {}  # project_name -> latest ISO timestamp
        for conv_id, proj_name in conv_to_project.items():
            transcript = os.path.join(self.brain_dir, conv_id, '.system_generated', 'logs', 'transcript.jsonl')
            if not os.path.exists(transcript):
                continue
            try:
                with open(transcript, 'rb') as f:
                    f.seek(0, 2)
                    size = f.tell()
                    f.seek(max(0, size - 200000))
                    chunk = f.read().decode('utf-8', errors='replace')
                    for line in reversed(chunk.splitlines()):
                        if not line.strip():
                            continue
                        try:
                            d = json.loads(line)
                            ts = d.get('created_at', '')
                            if ts:
                                if proj_name not in project_last_user or ts > project_last_user[proj_name]:
                                    project_last_user[proj_name] = ts
                                break
                        except Exception:
                            pass
            except Exception:
                continue

        # Step 4: Apply timestamps and sort
        # Sort: Projects with conversations (most recent first) -> Projects without conversations (by file mtime)
        for p in projects:
            p['lastActive'] = project_last_user.get(p['name'], "")
            p['is_recent'] = bool(p['lastActive'])

        projects.sort(key=lambda x: (
            1 if x["is_recent"] else 0,
            x["lastActive"] if x["is_recent"] else x.get("_json_mtime", 0)
        ), reverse=True)

        # Clean up internal fields before returning
        for p in projects:
            p.pop('lastActive', None)
            p.pop('is_recent', None)
            p.pop('_json_mtime', None)

        return projects

    def find_conversations_for_project(self, folder_name: str, project_id: str = None) -> list[dict]:
        """Find conversations belonging to a project using authoritative DB metadata.
        Returns list of {id, lastActive, firstMessage} dicts, sorted by recency."""
        if (not folder_name and not project_id) or not os.path.isdir(self.brain_dir):
            return []

        # Build the target path pattern from the folder name
        # folder_name is like "Tether", "Curator", etc.
        target_lower = folder_name.lower() if folder_name else None

        # Scan conversation DBs for matching workspace URIs
        matching_convs = []  # (conv_id, transcript_path, mtime)
        if os.path.isdir(self.conversations_dir):
            for db_file in os.listdir(self.conversations_dir):
                if not db_file.endswith('.db'):
                    continue
                conv_id = db_file[:-3]
                db_path = os.path.join(self.conversations_dir, db_file)
                transcript = os.path.join(self.brain_dir, conv_id, ".system_generated", "logs", "transcript.jsonl")
                if not os.path.exists(transcript):
                    continue
                try:
                    conn = sqlite3.connect(db_path)
                    cursor = conn.cursor()
                    cursor.execute("SELECT data FROM trajectory_metadata_blob WHERE id='main' LIMIT 1")
                    row = cursor.fetchone()
                    conn.close()
                    if row and isinstance(row[0], bytes):
                        matched = False
                        if target_lower:
                            uris = re.findall(rb'file:///[a-zA-Z]:/[^\x00-\x1f\x80-\x9f]+', row[0])
                            for uri_bytes in uris:
                                uri_str = uri_bytes.decode('utf-8', errors='replace')
                                m = re.match(r'file:///[a-zA-Z]:/[^"\x00-\x1f]*', uri_str)
                                if m:
                                    path = m.group(0)[8:]  # strip file:///
                                    # Check if this conversation's workspace ends with the target folder
                                    path_parts = path.rstrip('/').lower().split('/')
                                    if path_parts and path_parts[-1] == target_lower:
                                        matched = True
                                        break
                                        
                        if not matched and project_id:
                            uuids = re.findall(rb'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', row[0])
                            for uuid_bytes in uuids:
                                if uuid_bytes.decode('utf-8') == project_id:
                                    matched = True
                                    break
                                    
                        if matched:
                            mtime = os.path.getmtime(transcript)
                            matching_convs.append((conv_id, transcript, mtime))
                except Exception:
                    continue

        # Sort by mtime descending (most recent first)
        matching_convs.sort(key=lambda x: x[2], reverse=True)

        # Build child_to_parent mapping by scanning transcripts for invoke_subagent responses
        child_to_parent = {}
        for conv_id, transcript_path, _ in matching_convs:
            try:
                with open(transcript_path, 'r', encoding='utf-8', errors='ignore') as f:
                    for line in f:
                        if 'invoke_subagent' in line and 'TOOL_RESPONSE' in line:
                            try:
                                d = json.loads(line)
                                if d.get('type') == 'TOOL_RESPONSE' and 'invoke_subagent' in d.get('name', ''):
                                    c = d.get('content', '')
                                    ids = re.findall(r'([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})', c)
                                    for sub_id in ids:
                                        if sub_id != conv_id:
                                            child_to_parent[sub_id] = conv_id
                            except Exception:
                                pass
            except Exception:
                pass

        # Extract first user message preview from each matching conversation
        matches = []
        for conv_id, transcript_path, mtime in matching_convs:
            try:
                first_user_msg = ""
                with open(transcript_path, 'r', encoding='utf-8', errors='replace') as f:
                    for _ in range(self.transcript_scan_lines):
                        line = f.readline()
                        if not line:
                            break
                        try:
                            d = json.loads(line)
                            if d.get('type') == 'USER_INPUT' and not first_user_msg:
                                match = re.search(
                                    r'<USER_REQUEST>(.*?)(?:</USER_REQUEST>|$)',
                                    d.get('content', ''), re.DOTALL
                                )
                                if match:
                                    first_user_msg = match.group(1).strip()[:120]
                                else:
                                    first_user_msg = d.get('content', '').strip()[:120]
                                break
                        except (json.JSONDecodeError, Exception):
                            continue

                matches.append({
                    "id": conv_id,
                    "parentId": child_to_parent.get(conv_id, ""),
                    "lastActive": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(mtime)),
                    "firstMessage": first_user_msg
                })
            except Exception:
                continue

        return matches

    def create_project(self, project_name: str) -> None:
        """Create a new project directory and configuration."""
        import sys
        
        project_name = project_name.strip()
        if not project_name:
            print("Error: Missing project name in create_project", file=sys.stderr)
            return
        
        print(f"[CREATE_PROJECT] Starting creation: '{project_name}'", flush=True)
        
        # 1. Create directory
        dev_dir = r"C:\Development"
        project_path = os.path.join(dev_dir, project_name)
        os.makedirs(project_path, exist_ok=True)
        print(f"[CREATE_PROJECT] Directory created: {project_path} (exists={os.path.isdir(project_path)})", flush=True)
        
        # 2. Create Rover JSON
        proj_id = str(uuid.uuid4())
        folder_uri = f"file:///c%3A/Development/{urllib.parse.quote(project_name)}"
        print(f"[CREATE_PROJECT] Generated ID: {proj_id}, folderUri: {folder_uri}", flush=True)
        
        proj_json = {
          "id": proj_id,
          "name": project_name,
          "projectResources": {
            "resources": [
              {
                "gitFolder": {
                  "folderUri": folder_uri,
                  "defaultBranch": "main",
                  "allowWrite": True
                }
              }
            ]
          },
          "permissionGrants": {
            "permissionGrants": {
              "allow": [
                "command(@\")"
              ]
            }
          }
        }
        
        json_path = os.path.join(self.projects_dir, f"{proj_id}.json")
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(proj_json, f, indent=2)
        print(f"[CREATE_PROJECT] JSON written: {json_path} (exists={os.path.isfile(json_path)})", flush=True)

    def update_project_settings(self, project_id: str, is_turbo: bool):
        json_path = os.path.join(self.projects_dir, f"{project_id}.json")
        if not os.path.exists(json_path):
            return False
        try:
            with open(json_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            
            if "settings" not in data:
                data["settings"] = {}
                
            data["settings"]["fileAccessPolicy"] = "AGENT_SETTING_POLICY_ALLOW" if is_turbo else "AGENT_SETTING_POLICY_ASK"
            data["settings"]["autoExecutionPolicy"] = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER" if is_turbo else "CASCADE_COMMANDS_AUTO_EXECUTION_OFF"
            
            from datetime import datetime, timezone
            data["updatedAt"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            
            with open(json_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
            return True
        except Exception as e:
            print(f"Error updating project settings: {e}")
            return False
