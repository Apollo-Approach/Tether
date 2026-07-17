const { execSync } = require('child_process');

const patterns = [
    'AKIA[0-9A-Z]{16}',
    'sk-[a-zA-Z0-9]{48}',
    'ghp_[a-zA-Z0-9]{36}',
    'xox[baprs]-[0-9]{12}-[0-9]{12}-[a-zA-Z0-9]{24}',
    'AIza[0-9A-Za-z\\\\-_]{35}'
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

