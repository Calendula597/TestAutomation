const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("demoApi", {
  saveNote: (note) => ipcRenderer.invoke("notes:add", note),
});
