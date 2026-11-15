Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$form = New-Object System.Windows.Forms.Form
$form.Text = "原生 Windows 演示"
$form.Width = 480
$form.Height = 220

$noteInput = New-Object System.Windows.Forms.TextBox
$noteInput.Name = "noteInput"
$noteInput.Left = 24
$noteInput.Top = 24
$noteInput.Width = 300

$save = New-Object System.Windows.Forms.Button
$save.Name = "saveButton"
$save.Text = "保存"
$save.Left = 340
$save.Top = 20
$save.Width = 90

$status = New-Object System.Windows.Forms.Label
$status.Name = "statusLabel"
$status.Text = "尚未保存"
$status.Left = 24
$status.Top = 80
$status.Width = 400

$save.Add_Click({
    $directory = $env:NATIVE_DEMO_USER_DATA
    if (-not $directory) {
        $directory = Join-Path $env:TEMP "native-demo-default"
    }
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $directory "note.txt"), $noteInput.Text)
    $status.Text = "已保存 " + $noteInput.Text
})

$form.Controls.Add($noteInput)
$form.Controls.Add($save)
$form.Controls.Add($status)
$form.ShowDialog() | Out-Null
