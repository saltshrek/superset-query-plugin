// jsdom, plus Node's own fetch and Response under names the panel doesn't
// use. Test helpers use them to talk to code-source over real HTTP.
const { TestEnvironment } = require('jest-environment-jsdom');

class Environment extends TestEnvironment {
  constructor(config, context) {
    super(config, context);
    this.global.nodeFetch = fetch;
    this.global.Response = Response;
  }
}

module.exports = Environment;
