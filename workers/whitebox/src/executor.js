import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const demoDirectory = fileURLToPath(new URL("../demo", import.meta.url));
const expectedExclusions = ["src/legacy.js"];

class ControlError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function checkControl(control) {
  const status = await control.cancelled();
  if (status === true || status === "CANCELLED") {
    throw new ControlError("CANCELLED", "执行已取消");
  }
  if (status === "TIMED_OUT" || Date.now() >= control.deadline()) {
    throw new ControlError("TIMED_OUT", "执行超时");
  }
}

async function outcomeError(error, control) {
  if (error instanceof ControlError) {
    return error;
  }
  try {
    await checkControl(control);
  } catch (controlError) {
    if (controlError instanceof ControlError) {
      return controlError;
    }
  }
  return error;
}

async function runCommand(command, args, cwd, logName, control) {
  const output = createWriteStream(logName);
  const child = spawn(command, args, {
    cwd,
    env: { ...process.env, CI: "1", npm_config_loglevel: "error" },
    detached: process.platform !== "win32",
    shell: process.platform === "win32",
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.pipe(output);
  child.stderr.pipe(output);
  const closed = new Promise((resolve) => child.once("close", resolve));
  let settled = false;
  try {
    while (!settled) {
      await checkControl(control);
      await new Promise((resolve) => setTimeout(resolve, 50));
      settled = child.exitCode !== null || child.signalCode !== null;
    }
    await closed;
    await new Promise((resolve) => output.end(resolve));
    await checkControl(control);
  } catch (error) {
    await killTree(child);
    output.destroy();
    throw error;
  }
  return child.exitCode;
}

async function killTree(child) {
  if (process.platform === "win32") {
    await execFileAsync("taskkill", ["/pid", String(child.pid), "/T", "/F"]).catch(() => {});
    return;
  }
  try {
    process.kill(-child.pid, "SIGTERM");
  } catch {
    try {
      child.kill("SIGTERM");
    } catch {
      // The process already exited.
    }
  }
}

function evidenceEntry(kind, storageKey, filename, contentType) {
  return {
    id: crypto.randomUUID(),
    kind,
    path: storageKey,
    filename,
    contentType,
  };
}

function thresholdResults(thresholds, coverage) {
  return Object.entries(thresholds).map(([name, expected]) => ({
    name,
    expected,
    actual: coverage[name],
    passed: coverage[name] >= expected,
  }));
}

export async function executeWhitebox(preparation, control) {
  const startedAt = Date.now();
  const execution = preparation.caseVersion.execution;
  if (execution.project !== "demo-js") {
    throw new Error("白盒 Worker 只接受固定的 demo-js 项目");
  }
  if (!["pass", "fail-tests", "slow", "partial", "tool-error"].includes(execution.scenario)) {
    throw new Error("不支持的白盒演示场景");
  }
  if (execution.scenario === "tool-error") {
    throw new Error("Vitest 执行器不可用");
  }
  const thresholds = execution.coverage.thresholds;
  const exclusions = execution.coverage.exclude || [];
  if (JSON.stringify(exclusions) !== JSON.stringify(expectedExclusions)) {
    throw new Error("覆盖率排除规则必须与版本化 demo 配置一致");
  }

  const evidenceRoot = process.env.EVIDENCE_DIR || os.tmpdir();
  await fs.mkdir(evidenceRoot, { recursive: true });
  const runDirectory = await fs.mkdtemp(path.join(evidenceRoot, "whitebox-worker-"));
  const projectDirectory = path.join(runDirectory, "project");
  const evidence = [];
  let outcome;
  try {
    await fs.cp(demoDirectory, projectDirectory, { recursive: true });
    if (execution.scenario === "partial") {
      await fs.rm(path.join(projectDirectory, "test/math.test.js"));
    }
    if (execution.scenario === "fail-tests") {
      await fs.copyFile(path.join(demoDirectory, "scenarios/failing.test.js"),
        path.join(projectDirectory, "test/failing.test.js"));
    }
    if (execution.scenario === "slow") {
      await fs.copyFile(path.join(demoDirectory, "scenarios/slow.test.js"),
        path.join(projectDirectory, "test/slow.test.js"));
    }

    await checkControl(control);
    const npm = process.platform === "win32" ? "npm.cmd" : "npm";
    const installLog = path.join(runDirectory, "install.log");
    await runCommand(npm, ["ci", "--ignore-scripts"], projectDirectory, installLog, control);
    const outputLog = path.join(runDirectory, "output.log");
    const testExit = await runCommand(npm,
      ["run", "test:coverage", "--", "--reporter=json", "--outputFile=results.json"],
      projectDirectory, outputLog, control);
    const resultFile = path.join(projectDirectory, "results.json");
    const coverageFile = path.join(projectDirectory, "coverage/coverage-summary.json");
    const testResult = JSON.parse(await fs.readFile(resultFile, "utf8"));
    let coverageSummary = null;
    try {
      coverageSummary = JSON.parse(await fs.readFile(coverageFile, "utf8"));
    } catch (error) {
      if (testResult.success) {
        throw error;
      }
    }
    const coverage = coverageSummary ? {
      statements: coverageSummary.total.statements.pct,
      branches: coverageSummary.total.branches.pct,
      functions: coverageSummary.total.functions.pct,
    } : { statements: 0, branches: 0, functions: 0 };
    const thresholdResult = thresholdResults(thresholds, coverage);
    const coveragePassed = thresholdResult.every((result) => result.passed);
    const testsPassed = testResult.success && testExit === 0;
    const category = testsPassed ? (coveragePassed ? "PASSED" : "COVERAGE_FAILED") : "TEST_FAILED";
    const testResults = {
      total: testResult.numTotalTests,
      passed: testResult.numPassedTests,
      failed: testResult.numFailedTests,
      exitCode: testExit,
    };
    const enginePackage = JSON.parse(await fs.readFile(
      path.join(projectDirectory, "node_modules/vitest/package.json"), "utf8"));
    const preservedResult = path.join(runDirectory, "results.json");
    const preservedCoverage = path.join(runDirectory, "coverage-summary.json");
    const preservedConfig = path.join(runDirectory, "vitest.config.js");
    await fs.copyFile(resultFile, preservedResult);
    if (coverageSummary) {
      await fs.copyFile(coverageFile, preservedCoverage);
    }
    await fs.copyFile(path.join(projectDirectory, "vitest.config.js"), preservedConfig);
    const artifacts = [
      ["TEST_RESULT", preservedResult, "results.json", "application/json"],
      ["TOOL_OUTPUT", outputLog, "output.log", "text/plain"],
      ["TOOL_OUTPUT", installLog, "install.log", "text/plain"],
      ["COVERAGE_CONFIG", preservedConfig, "vitest.config.js", "application/javascript"],
    ];
    if (coverageSummary) {
      artifacts.push(["COVERAGE_SUMMARY", preservedCoverage, "coverage-summary.json", "application/json"]);
    }
    for (const [kind, file, filename, contentType] of artifacts) {
      const storageKey = `${path.basename(runDirectory)}/${path.relative(runDirectory, file).replaceAll("\\", "/")}`;
      evidence.push({ ...evidenceEntry(kind, storageKey, filename, contentType) });
    }
    outcome = {
      status: testsPassed && coveragePassed ? "PASSED" : "ASSERTION_FAILED",
      category,
      reason: testsPassed ? (coveragePassed ? null : "覆盖率未达到项目门槛") : "单元或集成测试失败",
      testResults,
      coverage,
      thresholds: thresholdResult,
      exclusions,
      evidence,
      durationMs: Date.now() - startedAt,
      engine: "Vitest",
      engineVersion: enginePackage.version,
    };
  } catch (error) {
    const failure = await outcomeError(error, control);
    outcome = {
      status: failure instanceof ControlError ? failure.status : "ERROR",
      category: failure instanceof ControlError ? failure.status : "TOOL_ERROR",
      reason: failure.message,
      testResults: { total: 0, passed: 0, failed: 0 },
      coverage: { statements: 0, branches: 0, functions: 0 },
      thresholds: thresholdResults(thresholds, { statements: 0, branches: 0, functions: 0 }),
      exclusions,
      evidence,
      durationMs: Date.now() - startedAt,
      engine: "Vitest",
      engineVersion: "5.1.0",
    };
  } finally {
    await fs.rm(projectDirectory, { recursive: true, force: true });
  }
  return outcome;
}
