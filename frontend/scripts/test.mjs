import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { executablePath } from 'puppeteer';

const cli = fileURLToPath(new URL('../node_modules/@angular/cli/bin/ng.js', import.meta.url));
const result = spawnSync(process.execPath, [cli, 'test', ...process.argv.slice(2)], {
  stdio: 'inherit',
  env: { ...process.env, CHROME_BIN: process.env.CHROME_BIN || await executablePath() }
});

if (result.error) {
  console.error(result.error.message);
}
process.exit(result.status ?? 1);
