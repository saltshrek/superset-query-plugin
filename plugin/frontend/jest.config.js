// Panel tests: the real panel in jsdom, against a real code-source on a
// fixture repository. See "Commands" in plugin/AGENTS.md.
module.exports = {
  testEnvironment: '<rootDir>/test/environment.js',
  globalSetup: '<rootDir>/test/globalSetup.js',
  setupFilesAfterEnv: ['<rootDir>/test/setup.ts'],
  testMatch: ['<rootDir>/test/**/*.test.ts?(x)'],
  transform: {
    '^.+\\.tsx?$': [
      'ts-jest',
      {
        tsconfig: {
          jsx: 'react',
          module: 'commonjs',
          target: 'es2019',
          strict: true,
          esModuleInterop: true,
          skipLibCheck: true,
          types: ['jest', '@testing-library/jest-dom', 'node'],
        },
      },
    ],
  },
  moduleNameMapper: {
    // Provided by Superset at runtime; faked at the module boundary.
    '^@apache-superset/core$': '<rootDir>/test/fakes/superset-core.tsx',
  },
  // Starting code-source (a JVM) takes a couple of seconds.
  testTimeout: 30000,
};
