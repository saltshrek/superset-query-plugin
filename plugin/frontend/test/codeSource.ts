// A real code-source process, started from its Gradle build (globalSetup).
import { ChildProcess, spawn } from 'child_process';
import fs from 'fs';
import net from 'net';
import os from 'os';
import path from 'path';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { CODE_SOURCE_BIN } = require('./paths');

// Node's fetch, put on the global object by environment.js. The panel's
// `fetch` is something else (see backend.ts).
declare const nodeFetch: typeof fetch;

async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, () => {
      const { port } = server.address() as net.AddressInfo;
      server.close(() => resolve(port));
    });
  });
}

async function waitFor(
  check: () => Promise<boolean>,
  what: string,
  timeoutMs = 20000,
) {
  const deadline = Date.now() + timeoutMs;
  while (!(await check().catch(() => false))) {
    if (Date.now() > deadline) throw new Error(`Timed out waiting for ${what}`);
    await new Promise(r => setTimeout(r, 100));
  }
}

export class CodeSource {
  private process?: ChildProcess;
  private readonly dataDir = fs.mkdtempSync(
    path.join(os.tmpdir(), 'code-source-'),
  );

  private constructor(
    private readonly repoUrl: string,
    private readonly port: number,
  ) {}

  /** Starts code-source on `repoUrl` and waits until it serves the tree. */
  static async start(repoUrl: string): Promise<CodeSource> {
    const codeSource = new CodeSource(repoUrl, await freePort());
    await codeSource.start();
    return codeSource;
  }

  get url(): string {
    return `http://127.0.0.1:${this.port}`;
  }

  /** (Re)starts the process on the same port. */
  async start(): Promise<void> {
    this.process = spawn(CODE_SOURCE_BIN, [], {
      env: {
        ...process.env,
        PORT: String(this.port),
        REPO_URL: this.repoUrl,
        DATA_DIR: this.dataDir,
        // Tests refresh explicitly.
        REFRESH_INTERVAL_SECONDS: '0',
      },
      stdio: 'ignore',
    });
    await waitFor(
      async () => (await nodeFetch(`${this.url}/api/v1/tree`)).ok,
      'code-source to serve the tree',
    );
  }

  async stop(): Promise<void> {
    const child = this.process;
    if (!child || child.exitCode !== null) return;
    await new Promise(resolve => {
      child.once('exit', resolve);
      child.kill();
    });
  }

  async refresh(): Promise<void> {
    const response = await nodeFetch(`${this.url}/refresh`, { method: 'POST' });
    if (!response.ok)
      throw new Error(`refresh failed: ${await response.text()}`);
  }

  async remove(): Promise<void> {
    await this.stop();
    fs.rmSync(this.dataDir, { recursive: true, force: true });
  }
}
