const fs = require('fs');
const { execSync } = require('child_process');

const CHANGELOG_PATH = './CHANGELOG.md';
const POM_PATH = './pom.xml';

function updateChangelog() {
    // 1. Get version from pom.xml, stripping -SNAPSHOT
    const pomContent = fs.readFileSync(POM_PATH, 'utf8');
    const match = pomContent.match(/<version>([^<]+)<\/version>/);
    if (!match) {
        console.error('❌ Could not find <version> in pom.xml');
        process.exit(1);
    }
    const rawVersion = match[1].trim();
    const cleanVersion = rawVersion.replace(/-SNAPSHOT$/, '');
    const headerTitle = `## v${cleanVersion}`;

    const changelogContent = fs.readFileSync(CHANGELOG_PATH, 'utf8');

    // 2. Fetch and filter git log entries
    function getLogEntries(since) {
        try {
            let cmd;
            if (since) {
                cmd = `git log --since="${since}" --oneline`;
            } else {
                const lastTag = execSync('git describe --tags --abbrev=0').toString().trim();
                cmd = `git log ${lastTag}..HEAD --oneline`;
            }
            const rawLogs = execSync(cmd).toString().split('\n');
            return rawLogs
                .map(line => {
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
                    if (l.includes('spotless:apply')) return false;
                    return l.startsWith('feature/') || l.startsWith('bugfix/') || l.startsWith('task/') || !line.includes('/');
                });
        } catch (e) {
            return [];
        }
    }

    // 3. Existing section for this clean version — append new entries
    if (changelogContent.includes(headerTitle)) {
        const lines = changelogContent.split('\n');
        const sectionStart = lines.findIndex(l => l.trim() === headerTitle);

        // Find section end (next ## or end of file)
        let sectionEnd = lines.findIndex((l, i) => i > sectionStart && l.startsWith('## '));
        if (sectionEnd === -1) sectionEnd = lines.length;

        const sectionLines = lines.slice(sectionStart, sectionEnd);

        // Extract existing date to use as lower bound for new commits
        const dateLine = sectionLines.find(l => l.startsWith('Date: '));
        const existingDate = dateLine ? dateLine.replace('Date: ', '').trim() : null;

        const newEntries = getLogEntries(existingDate);

        // Deduplicate against bullets already present in the section
        const existingBullets = new Set(
            sectionLines.filter(l => l.startsWith('* ')).map(l => l.slice(2).trim())
        );
        const toAdd = newEntries
            .filter(e => !existingBullets.has(e))
            .map(e => `* ${e}`);

        if (toAdd.length === 0) {
            console.log(`⚠️  No new entries to add for v${cleanVersion}. Skipping update.`);
            return;
        }

        // Insert after the last bullet in the section
        let insertAt = -1;
        for (let i = sectionStart; i < sectionEnd; i++) {
            if (lines[i].startsWith('* ')) insertAt = i;
        }
        // No existing bullets — insert at section end
        if (insertAt === -1) insertAt = sectionEnd - 1;

        const newLines = [
            ...lines.slice(0, insertAt + 1),
            ...toAdd,
            ...lines.slice(insertAt + 1)
        ];

        fs.writeFileSync(CHANGELOG_PATH, newLines.join('\n'));
        console.log(`✅ Added ${toAdd.length} new entries to existing v${cleanVersion} section.`);
        return;
    }

    // 4. No existing section — create a new one at the top
    console.log(`🚀 Creating new changelog section for v${cleanVersion}...`);

    const date = new Date().toISOString().split('T')[0];
    const newEntries = getLogEntries(null);
    const bulletLines = newEntries.length > 0
        ? newEntries.map(e => `* ${e}`)
        : ['* Initial changes for this version.'];

    const fullHeader = `\n${headerTitle}\n\nDate: ${date}\n`;

    const lines = changelogContent.split('\n');
    let insertIndex = lines.findIndex(l => l.startsWith('## '));
    if (insertIndex === -1) insertIndex = 2;

    const newContent = [
        ...lines.slice(0, insertIndex),
        fullHeader,
        bulletLines.join('\n'),
        "\n",
        ...lines.slice(insertIndex)
    ].join('\n');

    fs.writeFileSync(CHANGELOG_PATH, newContent);
    console.log(`✅ CHANGELOG.md updated successfully.`);
}

updateChangelog();
