const { app, BrowserWindow, ipcMain } = require("electron");
const fs = require("node:fs");
const path = require("node:path");

if (process.env.ELECTRON_DEMO_USER_DATA_DIR) {
  app.setPath("userData", process.env.ELECTRON_DEMO_USER_DATA_DIR);
}

const notesFile = () => path.join(app.getPath("userData"), "notes.json");

function readNotes() {
  try {
    return JSON.parse(fs.readFileSync(notesFile(), "utf8"));
  } catch {
    return [];
  }
}

ipcMain.handle("notes:add", (_event, note) => {
  const notes = readNotes();
  notes.push(note);
  fs.writeFileSync(notesFile(), JSON.stringify(notes), "utf8");
  return notes;
});

if (process.env.ELECTRON_DEMO_TEST_HOOKS === "1") {
  globalThis.__electronTestHooks = {
    getSavedNotes: () => readNotes(),
  };
}

function createWindow() {
  const window = new BrowserWindow({
    show: false,
    width: 800,
    height: 600,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  window.loadFile(path.join(__dirname, "index.html"));
}

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", () => {
  app.quit();
});
