const fs = require('fs');
const { execSync } = require('child_process');

const CHANGELOG_PATH = './CHANGELOG.md';
const POM_PATH = './pom.xml';

function updateChangelog() {
    // 1. Get Version Info from pom.xml
    const pomContent = fs.readFileSync(POM_PATH, 'utf8');
    const match = pomContent.match(/<version>([^<]+)<\/version>/);
    if (!match) {
        console.error('❌ Could not find <version> in pom.xml');
        process.exit(1);
    }
    const version = match[1].trim();

    // 2. Determine Type (SNAPSHOT = in-development, else release)
    const isSnapshot = version.endsWith('-SNAPSHOT');
    const date = new Date().toISOString().split('T')[0];
    const headerTitle = isSnapshot ? `## v${version} (snapshot)` : `## v${version}`;

    // The exact format required by CI: [Blank][Header][Blank][Date]
    const fullHeader = `\n${headerTitle}\n\nDate: ${date}\n`;

    // 3. Safety Check: Does this version already exist?
    const changelogContent = fs.readFileSync(CHANGELOG_PATH, 'utf8');
    if (changelogContent.includes(headerTitle)) {
        console.log(`⚠️  Entry for ${version} already exists. Skipping update.`);
        return;
    }

    console.log(`🚀 Preparing ${isSnapshot ? 'Snapshot' : 'Release'} logs for v${version}...`);

    // 4. Fetch and Filter Git Logs
    let logEntries = [];
    try {
        // Gets all commits since the last tag
        const lastTag = execSync('git describe --tags --abbrev=0').toString().trim();
        const rawLogs = execSync(`git log ${lastTag}..HEAD --oneline`).toString().split('\n');

        logEntries = rawLogs
            .map(line => {
                // Remove hash
                let msg = line.replace(/^[a-f0-9]+\s/, '').trim();
                // Clean GitHub PR merges: "Merge pull request #1 from user/feature/xyz" -> "feature/xyz"
                const prMatch = msg.match(/Merge pull request #\d+ from .+\/(.+)/);
                if (prMatch) msg = prMatch[1];
                // Clean generic merges: "Merge branch 'task/abc'" -> "task/abc"
                msg = msg.replace(/^Merge branch '(.*)'$/, '$1');
                return msg;
            })
            .filter(line => line.length > 0)
            .filter(line => {
                const l = line.toLowerCase();
                return l.startsWith('feature/') || l.startsWith('bugfix/') || l.startsWith('task/') || !line.includes('/');
            })
            .map(line => `* ${line}`);

    } catch (e) {
        logEntries = ["* Initial changes for this version."];
    }

    // 5. Insert into Changelog
    const lines = changelogContent.split('\n');
    // Find first existing H2 to insert above it, otherwise insert after the main title
    let insertIndex = lines.findIndex(l => l.startsWith('## '));
    if (insertIndex === -1) insertIndex = 2;

    const newContent = [
        ...lines.slice(0, insertIndex),
        fullHeader,
        logEntries.join('\n'),
        "\n",
        ...lines.slice(insertIndex)
    ].join('\n');

    fs.writeFileSync(CHANGELOG_PATH, newContent);
    console.log(`✅ CHANGELOG.md updated successfully.`);
}

updateChangelog();
