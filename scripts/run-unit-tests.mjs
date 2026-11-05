import { readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Mocha from 'mocha';

// If you plan to add more tests, consider replacing Mocha with Vitest: https://vitest.dev/

const repositoryRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const testDirectory = path.join(repositoryRoot, 'viite-UI', 'test', 'unit-tests');
const testFiles = readdirSync(testDirectory)
  .filter((fileName) => fileName.endsWith('.js'))
  .sort();
const mocha = new Mocha({ reporter: 'spec' }); 

if (testFiles.length === 0) {
  throw new Error(`No unit-test files were found in ${testDirectory}`);
}

for (const testFile of testFiles) {
  mocha.addFile(path.join(testDirectory, testFile));
}

async function runTests() {
  await mocha.loadFilesAsync();
  return new Promise((resolve) => mocha.run(resolve));
}

runTests().then((failures) => {
  process.exitCode = failures === 0 ? 0 : 1;
});
