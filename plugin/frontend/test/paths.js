const path = require('path');

const CODE_SOURCE_DIR = path.resolve(__dirname, '../../../code-source');

module.exports = {
  CODE_SOURCE_DIR,
  CODE_SOURCE_BIN: path.join(
    CODE_SOURCE_DIR,
    'build/install/code-source/bin/code-source',
  ),
};
