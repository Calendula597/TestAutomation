import { execFile } from "node:child_process";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import electronPath from "electron";
import { _electron as electron } from "playwright";

const execFileAsync = promisify(execFile);
const demoMain = fileURLToPath(new URL("../demo/main.cjs", import.meta.url));
const MASK = "******";
const ACTION_TIMEOUT_MS = 10_000;

class RunControlError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function substitute(template, variables) {
  if (template === null || template === undefined) {
    return template;
  }
  return String(template).replace(/\{\{([A-Za-z0-9_.-]+)}}/g, (_match, key) => {
    if (!(key in variables)) {
      throw new Error(`变量未定义: ${key}`);
    }
    return variables[key];
  });
}

function mask(value, sensitiveValues) {
  if (value === null || value === undefined) {
    return value;
  }
  let result = String(value);
  for (const secret of sensitiveValues
    .filter(Boolean)
    .sort((a, b) => b.length - a.length)) {
    result = result.split(secret).join(MASK);
  }
  return result;
}

function remainingTimeout(control) {
  return Math.max(
    1,
    Math.min(ACTION_TIMEOUT_MS, control.deadline() - Date.now()),
  );
}

async function checkControl(control) {
  const status = await control.cancelled();
  if (status === true || status === "CANCELLED") {
    throw new RunControlError("CANCELLED", "执行已取消");
  }
  if (status === "TIMED_OUT" || Date.now() >= control.deadline()) {
    throw new RunControlError("TIMED_OUT", "执行超时");
  }
}

async function outcomeError(error, control) {
  if (error instanceof RunControlError) {
    return error;
  }
  try {
    await checkControl(control);
  } catch (controlError) {
    if (controlError instanceof RunControlError) {
      return controlError;
    }
  }
  return error;
}

async function waitInterruptibly(milliseconds, control) {
  let remaining = Math.min(milliseconds, 60_000);
  while (remaining > 0) {
    await checkControl(control);
    const slice = Math.min(remaining, 50);
    await new Promise((resolve) => setTimeout(resolve, slice));
    remaining -= slice;
  }
  await checkControl(control);
}

async function callMainHook(app, name) {
  if (name !== "getSavedNotes") {
    throw new Error(`主进程钩子未显式声明: ${name}`);
  }
  return app.evaluate(async (_application, hookName) => {
    const hooks = globalThis.__electronTestHooks;
    if (!hooks || typeof hooks[hookName] !== "function") {
      throw new Error("应用未声明所需的主进程测试钩子");
    }
    return hooks[hookName]();
  }, name);
}

async function runStep(app, page, step, variables, sensitiveValues, control) {
  const outcome = {
    action: step.action,
    ...(step.selector ? { selector: step.selector } : {}),
  };
  try {
    await checkControl(control);
    const timeout = remainingTimeout(control);
    page.setDefaultTimeout(timeout);
    const value = substitute(step.value, variables);
    let actual;
    switch (step.action) {
      case "FILL":
        await page.locator(step.selector).fill(value, { timeout });
        actual = value;
        break;
      case "CLICK":
        await page.locator(step.selector).click({ timeout });
        actual = "clicked";
        break;
      case "ASSERT_TEXT": {
        const text =
          (await page.locator(step.selector).textContent({ timeout })) || "";
        outcome.expected = mask(value, sensitiveValues);
        outcome.actual = mask(text.trim(), sensitiveValues);
        outcome.status = text.includes(value) ? "PASSED" : "ASSERTION_FAILED";
        actual = text;
        break;
      }
      case "ASSERT_VISIBLE": {
        const visible = await page
          .locator(step.selector)
          .isVisible({ timeout });
        outcome.expected = "可见";
        outcome.actual = visible ? "可见" : "不可见";
        outcome.status = visible ? "PASSED" : "ASSERTION_FAILED";
        actual = visible ? "visible" : "invisible";
        break;
      }
      case "WAIT":
        await waitInterruptibly(Number(value || 0), control);
        actual = "waited";
        break;
      case "MAIN_HOOK": {
        const hooked = await callMainHook(app, step.name);
        const serialized = JSON.stringify(hooked);
        outcome.expected = mask(value, sensitiveValues);
        outcome.actual = mask(serialized, sensitiveValues);
        outcome.status = serialized.includes(value)
          ? "PASSED"
          : "ASSERTION_FAILED";
        actual = serialized;
        break;
      }
      default:
        throw new Error(`不支持的 Electron 步骤类型: ${step.action}`);
    }
    outcome.actual = mask(actual, sensitiveValues);
    if (!outcome.status) {
      outcome.status = "PASSED";
    }
  } catch (error) {
    const failure = await outcomeError(error, control);
    if (failure instanceof RunControlError) {
      throw failure;
    }
    outcome.status = "ERROR";
    outcome.error = mask(failure.message, sensitiveValues);
  }
  return outcome;
}

async function closeApplication(app) {
  if (!app) {
    return;
  }
  let closed = false;
  await Promise.race([
    app
      .close()
      .then(() => {
        closed = true;
      })
      .catch(() => {}),
    new Promise((resolve) => setTimeout(resolve, 5000)),
  ]);
  if (closed) {
    return;
  }
  const child = app.process();
  if (process.platform === "win32") {
    await execFileAsync("taskkill", [
      "/pid",
      String(child.pid),
      "/T",
      "/F",
    ]).catch(() => {});
  } else {
    try {
      process.kill(-child.pid, "SIGTERM");
    } catch {
      try {
        child.kill("SIGTERM");
      } catch {
        // The process has already exited.
      }
    }
  }
}

export async function executeElectron(preparation, control) {
  const startedAt = Date.now();
  const execution = preparation.caseVersion.execution;
  const variables = preparation.environment.variables;
  const sensitiveValues = preparation.environment.secretKeys.map(
    (key) => variables[key],
  );
  const steps = [];
  const runDirectory = await fs.mkdtemp(
    path.join(os.tmpdir(), "electron-worker-"),
  );
  let app;
  let outcome;
  try {
    await checkControl(control);
    app = await electron.launch({
      executablePath: process.env.ELECTRON_EXECUTABLE_PATH || electronPath,
      args: [demoMain],
      env: {
        ...process.env,
        ELECTRON_DEMO_USER_DATA_DIR: runDirectory,
        ELECTRON_DEMO_TEST_HOOKS: "1",
      },
      timeout: remainingTimeout(control),
    });
    const page = await app.firstWindow({ timeout: remainingTimeout(control) });
    for (let index = 0; index < execution.steps.length; index += 1) {
      await checkControl(control);
      const stepStartedAt = Date.now();
      const stepOutcome = await runStep(
        app,
        page,
        execution.steps[index],
        variables,
        sensitiveValues,
        control,
      );
      stepOutcome.index = index;
      stepOutcome.durationMs = Date.now() - stepStartedAt;
      steps.push(stepOutcome);
      if (
        stepOutcome.status === "ERROR" ||
        stepOutcome.status === "ASSERTION_FAILED"
      ) {
        outcome = {
          status: stepOutcome.status,
          reason: stepOutcome.error || "渲染或主进程断言未满足",
          steps,
          durationMs: Date.now() - startedAt,
        };
        break;
      }
    }
    if (!outcome) {
      await checkControl(control);
      outcome = {
        status: "PASSED",
        reason: null,
        steps,
        durationMs: Date.now() - startedAt,
      };
    }
  } catch (error) {
    const failure = await outcomeError(error, control);
    outcome = {
      status: failure instanceof RunControlError ? failure.status : "ERROR",
      reason: mask(failure.message, sensitiveValues),
      steps,
      durationMs: Date.now() - startedAt,
    };
  } finally {
    await closeApplication(app);
    await fs.rm(runDirectory, { recursive: true, force: true });
  }
  return outcome;
}
