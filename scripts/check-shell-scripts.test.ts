import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import type { TestContext } from "node:test";
import test from "node:test";

const wrapperPath = fileURLToPath(new URL("./check-shell-scripts", import.meta.url));
const wrapper = await readFile(wrapperPath, "utf8");

async function createScriptRepository(t: TestContext, scripts: Record<string, string>) {
  const directory = await mkdtemp(join(tmpdir(), "check-shell-scripts-"));
  t.after(() => rm(directory, { recursive: true, force: true }));

  const temporaryWrapper = join(directory, "check-shell-scripts");
  await writeFile(temporaryWrapper, wrapper);
  await chmod(temporaryWrapper, 0o755);
  execFileSync("git", ["init", "--quiet"], { cwd: directory });
  execFileSync("git", ["config", "core.autocrlf", "false"], { cwd: directory });

  for (const [file, contents] of Object.entries(scripts)) {
    const path = join(directory, file);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, contents);
  }

  execFileSync("git", ["add", "--", "check-shell-scripts", ...Object.keys(scripts)], { cwd: directory });
  return directory;
}

function runSyntaxCheck(directory: string) {
  const environment: NodeJS.ProcessEnv = { ...process.env };
  if (process.platform === "win32") {
    // Git for Windows rewrites /bin/bash inside the grep pattern unless path conversion is disabled.
    environment.MSYS_NO_PATHCONV = "1";
  }

  const result = spawnSync("bash", ["./check-shell-scripts", "syntax"], {
    cwd: directory,
    encoding: "utf8",
    env: environment,
  });
  assert.ifError(result.error);
  return result;
}

test("syntax mode accepts tracked Bash scripts without executing them", async (t) => {
  // given
  const directory = await createScriptRepository(t, {
    "first.sh": "#!/bin/bash\ntouch first.executed\n",
    "extensionless tool": "#!/usr/bin/env bash\ntouch extensionless.executed\n",
  });

  // when
  const result = runSyntaxCheck(directory);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.equal(existsSync(join(directory, "first.executed")), false);
  assert.equal(existsSync(join(directory, "extensionless.executed")), false);
});

test("syntax mode catches invalid later scripts whose filenames contain spaces", async (t) => {
  // given
  const invalidScript = "later invalid script";
  const directory = await createScriptRepository(t, {
    "first valid.sh": "#!/bin/bash\ntrue\n",
    [invalidScript]: "#!/bin/bash\nif then\n",
  });

  // when
  const result = runSyntaxCheck(directory);

  // then
  assert.equal(result.status, 2, "syntax errors in later tracked scripts must fail the gate");
  assert.match(`${result.stdout}${result.stderr}`, /later invalid script/);
  assert.equal(existsSync(join(directory, "later invalid script")), true);
});
