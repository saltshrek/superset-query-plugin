// A real git repository on local disk for code-source to clone via file://.
import { execFileSync } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { pathToFileURL } from 'url';

export class FixtureRepo {
  private readonly root = fs.mkdtempSync(path.join(os.tmpdir(), 'fixture-'));
  private readonly bare = path.join(this.root, 'remote.git');
  private readonly work = path.join(this.root, 'work');

  constructor() {
    this.git(this.root, 'init', '-q', '--bare', '-b', 'main', this.bare);
    this.git(this.root, 'init', '-q', '-b', 'main', this.work);
    this.git(this.work, 'remote', 'add', 'origin', this.bare);
  }

  get url(): string {
    return pathToFileURL(this.bare).href;
  }

  /** Pushes a commit containing exactly `files` (path → content) to main,
   * made at `date` (default now). */
  commit(files: Record<string, string>, { date }: { date?: Date } = {}): void {
    for (const entry of fs.readdirSync(this.work)) {
      if (entry !== '.git')
        fs.rmSync(path.join(this.work, entry), { recursive: true });
    }
    for (const [file, content] of Object.entries(files)) {
      const full = path.join(this.work, file);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, content);
    }
    this.git(this.work, 'add', '-A');
    const when = date?.toISOString();
    this.run(
      this.work,
      ['commit', '-q', '--allow-empty', '-m', 'fixture'],
      when ? { GIT_AUTHOR_DATE: when, GIT_COMMITTER_DATE: when } : {},
    );
    this.git(this.work, 'push', '-q', 'origin', 'HEAD:main');
  }

  remove(): void {
    fs.rmSync(this.root, { recursive: true, force: true });
  }

  private git(cwd: string, ...args: string[]): void {
    this.run(cwd, args, {});
  }

  private run(cwd: string, args: string[], env: Record<string, string>): void {
    execFileSync(
      'git',
      [
        '-c',
        'user.name=fixture',
        '-c',
        'user.email=fixture@example.com',
        ...args,
      ],
      {
        cwd,
        stdio: 'pipe',
        env: { ...process.env, ...env },
      },
    );
  }
}
