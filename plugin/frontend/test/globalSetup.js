// Builds code-source once per run; tests start it from the build.
const { execFileSync } = require('child_process');
const { CODE_SOURCE_DIR } = require('./paths');

module.exports = () => {
  execFileSync('./gradlew', ['--console=plain', '-q', 'installDist'], {
    cwd: CODE_SOURCE_DIR,
    stdio: 'inherit',
  });
};
