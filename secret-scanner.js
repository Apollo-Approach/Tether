const { execSync } = require('child_process');

const patterns = [
    // AWS Access Key
    'AKIA[0-9A-Z]{16}',
    
    // Google / Gemini API Key
    'AIza[0-9A-Za-z\\\\-_]{35}',
    
    // Tailscale Keys
    'tskey-(?:auth|api)-[a-zA-Z0-9]+-[a-zA-Z0-9]+',
    
    // OpenAI Keys (Legacy & New proj formats)
    'sk-[a-zA-Z0-9]{48}',
    'sk-proj-[a-zA-Z0-9\\\\-_]+',
    
    // Anthropic API Key
    'sk-ant-api[0-9a-zA-Z\\\\-_]+',
    
    // GitHub Personal Access Token
    'ghp_[a-zA-Z0-9]{36}',
    'github_pat_[a-zA-Z0-9]{22}_[a-zA-Z0-9]{59}',
    
    // Slack Tokens
    'xox[baprs]-[0-9]{12}-[0-9]{12}-[a-zA-Z0-9]{24}',
    
    // Generic Private Keys
    '-----BEGIN [A-Z ]+PRIVATE KEY-----'
];

try {
    const diff = execSync('git diff --cached', { maxBuffer: 1024 * 1024 * 50 }).toString();
    let found = false;
    for (const pattern of patterns) {
        const regex = new RegExp(pattern, 'g');
        if (regex.test(diff)) {
            console.error('\\x1b[31m%s\\x1b[0m', 'ERROR: Potential secret detected in staged files matching pattern: ' + pattern);
            found = true;
        }
    }
    if (found) {
        console.error('Commit aborted. Please remove the secrets from the code.');
        process.exit(1);
    }
    console.log('Secret scanner passed.');
} catch (e) {
    console.error('Error running secret scanner:', e);
    process.exit(1);
}

