// FoldCode's built-in VS Code extension. It talks to the Android app through two folders in
// the home directory (same user, same phone; nothing goes over the network):
//   ~/.foldcode/requests   VS Code → app   (open settings, GitHub sign-in)
//   ~/.foldcode/to-vscode  app → VS Code   (open a terminal running a command, open a folder)
'use strict';
const vscode = require('vscode');
const fs = require('fs');
const os = require('os');
const path = require('path');

const base = path.join(os.homedir(), '.foldcode');
const requests = path.join(base, 'requests');
const inbox = path.join(base, 'to-vscode');

function send(cmd, arg) {
  fs.mkdirSync(requests, { recursive: true });
  const name = `${Date.now()}-${Math.random().toString(36).slice(2)}.json`;
  const tmp = path.join(requests, `.${name}`);
  fs.writeFileSync(tmp, JSON.stringify({ cmd, arg }));
  fs.renameSync(tmp, path.join(requests, name)); // atomic: the app only sees complete files
}

function handle(file) {
  let msg;
  try { msg = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return; }
  try { fs.unlinkSync(file); } catch { /* already handled */ }
  if (msg.cmd === 'terminal' && typeof msg.text === 'string') {
    const t = vscode.window.createTerminal({ name: msg.name || 'FoldCode' });
    t.show();
    t.sendText(msg.text);
  } else if (msg.cmd === 'openFolder' && typeof msg.path === 'string') {
    vscode.commands.executeCommand('vscode.openFolder', vscode.Uri.file(msg.path), { forceNewWindow: false });
  }
}

function drain() {
  let names = [];
  try { names = fs.readdirSync(inbox).filter((n) => n.endsWith('.json') && !n.startsWith('.')); } catch { return; }
  for (const n of names.sort()) handle(path.join(inbox, n));
}

exports.activate = (ctx) => {
  const item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 1000);
  item.text = '$(device-mobile) FoldCode';
  item.tooltip = 'FoldCode settings: GitHub, phone files, keyboard, AI agents';
  item.command = 'foldcode.settings';
  item.show();
  ctx.subscriptions.push(item);

  ctx.subscriptions.push(vscode.commands.registerCommand('foldcode.settings', () => send('settings')));
  ctx.subscriptions.push(vscode.commands.registerCommand('foldcode.githubSignIn', () => send('github')));
  ctx.subscriptions.push(vscode.commands.registerCommand('foldcode.openPhoneFolder', async () => {
    const pick = await vscode.window.showQuickPick([
      { label: 'Download', description: '/sdcard/Download' },
      { label: 'Documents', description: '/sdcard/Documents' },
      { label: 'Whole phone storage', description: '/sdcard' },
    ], { placeHolder: 'Phone folder to open (needs file access in FoldCode settings)' });
    if (pick) vscode.commands.executeCommand('vscode.openFolder', vscode.Uri.file(pick.description));
  }));

  fs.mkdirSync(inbox, { recursive: true });
  drain();
  const watcher = fs.watch(inbox, () => drain());
  ctx.subscriptions.push({ dispose: () => watcher.close() });
};

exports.deactivate = () => {};
