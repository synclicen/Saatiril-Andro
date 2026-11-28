# Two real windows that stand in for Imaging Edge (Windows only):
#   "Imaging Edge"  light start page — offers Remote / Viewer / Edit, never shows a picture
#   "Remote"        green remote shooting window — this is the live view
#
# They stay open until -Seconds passed or the process is killed.
# usage: powershell.exe -NoProfile -ExecutionPolicy Bypass -File eos-windows.ps1 [-Seconds 120]
param([int]$Seconds = 120)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

function New-FakeWindow([string]$title, [string]$color, [string]$label, [int]$x) {
  $form = New-Object System.Windows.Forms.Form
  $form.Text = $title
  $form.StartPosition = 'Manual'
  $form.Location = New-Object System.Drawing.Point($x, 0)
  $form.ClientSize = New-Object System.Drawing.Size(400, 300)
  $form.FormBorderStyle = 'FixedSingle'
  $form.MaximizeBox = $false
  $form.TopMost = $true
  $form.BackColor = [System.Drawing.ColorTranslator]::FromHtml($color)
  $text = New-Object System.Windows.Forms.Label
  $text.Text = $label
  $text.AutoSize = $false
  $text.Location = New-Object System.Drawing.Point(0, 8)
  $text.Size = New-Object System.Drawing.Size(400, 50)
  $text.TextAlign = 'TopCenter'
  $text.Font = New-Object System.Drawing.Font('Arial', 18, [System.Drawing.FontStyle]::Bold)
  $text.ForeColor = [System.Drawing.Color]::Black
  $form.Controls.Add($text)
  $form.AddShown({ $this.Activate() })
  $form.Show()
  return $form
}

# Small enough for a 1024x768 desktop, side by side so neither covers the other.
$launcher = New-FakeWindow 'Imaging Edge' '#f0f0f0' 'LAUNCHER' 20
$remote = New-FakeWindow 'Remote' '#00a000' 'REMOTE' 480

# Ends the message loop (and with it the process) by itself — no process is left behind.
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = $Seconds * 1000
$timer.Add_Tick({ [System.Windows.Forms.Application]::Exit() })
$timer.Start()
[System.Windows.Forms.Application]::Run()
