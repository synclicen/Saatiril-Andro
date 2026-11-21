# Canon Camera CLI — uses WPD API (Windows Portable Devices) for Canon PTP control
# Commands:
#   list       — enumerate Canon cameras via WPD + PnP
#   liveview N — connect to camera N, get live-view frame (base64 JPEG)
#   capture N  — trigger shutter + download photo (base64 JPEG)
#
# Uses WPD API with explicit COM interop (vtable binding) — no external DLL.
# Works with Canon EOS cameras in PC Remote / PTP mode.

param(
    [Parameter(Position=0)][string]$Action = "list",
    [Parameter(Position=1)][string]$CameraId = ""
)

$ErrorActionPreference = "Continue"

# ── Minimal C# for PROPERTYKEY struct ──
if (-not ("WpdTypes" -as [type])) {
    try {
        Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
namespace WpdTypes {
    [StructLayout(LayoutKind.Sequential)]
    public struct PROPERTYKEY {
        public System.Guid fmtid;
        public int pid;
    }
}
"@ -ErrorAction Stop
    } catch {}
}

# WPD CLSIDs
$CLSID_Mgr = [Guid]"A1567595-4C2F-4574-802D-68D3C9E39E84"
$CLSID_Dev = [Guid]"7252987C-3411-4908-8A79-D5C5B43E1B7D"
$CLSID_Vals = [Guid]"D3877898-3A99-4B91-B947-6D31DEB8B5A5"

# WPD MTP command GUID
$CMD_MTP = [Guid]"26D0ACCA-A413-4cf2-9B53-6FFB1BAE9C33"
$MTP_GUID = [Guid]"B3A5DE3C-4E5F-4B89-9B7E-5DB6E9F9B53C"

function MakeKey($pid) {
    $k = New-Object WpdTypes.PROPERTYKEY
    $k.fmtid = $MTP_GUID
    $k.pid = $pid
    return $k
}

# Get Canon cameras from PnP (VID_04A9 = Canon)
function Get-CanonCameras {
    $cameras = @()
    try {
        $pnp = Get-PnpDevice | Where-Object {
            $_.InstanceId -match 'VID_04A9' -and
            ($_.Class -eq 'WPD' -or $_.Class -eq 'Camera' -or $_.Class -eq 'libusbK Usb Devices') -and
            ($_.Status -eq 'OK' -or $_.Status -eq 'Unknown')
        } | Sort-Object InstanceId -Unique

        $idx = 0
        foreach ($p in $pnp) {
            $cameras += @{ index = $idx; id = $p.InstanceId; name = $p.FriendlyName; model = $p.FriendlyName }
            $idx++
        }
    } catch {}
    return $cameras
}

switch ($Action.ToLower()) {
    "list" {
        $cameras = Get-CanonCameras
        if ($cameras.Count -eq 0) {
            @{ success = $true; cameras = @() } | ConvertTo-Json -Compress
        } else {
            @{ success = $true; cameras = $cameras } | ConvertTo-Json -Depth 3 -Compress
        }
    }

    "liveview" {
        try {
            $cameras = Get-CanonCameras
            $idx = [int]$CameraId
            if ($idx -ge $cameras.Count) {
                @{ success = $false; error = "Camera index out of range" } | ConvertTo-Json -Compress; break
            }
            $cam = $cameras[$idx]

            # Create PortableDevice + open
            $devType = [Type]::GetTypeFromCLSID($CLSID_Dev)
            $dev = [Activator]::CreateInstance($devType)
            $valsType = [Type]::GetTypeFromCLSID($CLSID_Vals)
            $clientInfo = [Activator]::CreateInstance($valsType)
            $dev.Open($cam.id, $clientInfo)
            $content = $dev.Content

            # Send Canon PTP commands via WPD SendCommand
            # OpenSession
            $cmd = [Activator]::CreateInstance($valsType)
            $cmd.SetGuidValue([ref](MakeKey 10), [ref]$CMD_MTP)
            $cmd.SetUnsignedIntegerValue([ref](MakeKey 11), [uint32]0)
            $cmd.SetUnsignedIntegerValue([ref](MakeKey 0), [uint32]0x1002)  # OpenSession
            $results = $null
            $content.SendCommand([uint32]0, $cmd, [ref]$results)

            # Enable live view (Canon vendor opcode 0x9120)
            $cmd2 = [Activator]::CreateInstance($valsType)
            $cmd2.SetGuidValue([ref](MakeKey 10), [ref]$CMD_MTP)
            $cmd2.SetUnsignedIntegerValue([ref](MakeKey 11), [uint32]0)
            $cmd2.SetUnsignedIntegerValue([ref](MakeKey 0), [uint32]0x9120)
            $cmd2.SetUnsignedIntegerValue([ref](MakeKey 1), [uint32]1)
            $results2 = $null
            try { $content.SendCommand([uint32]0, $cmd2, [ref]$results2) } catch {}

            Start-Sleep -Milliseconds 200

            # Get live view frame (Canon opcode 0x9180)
            $cmd3 = [Activator]::CreateInstance($valsType)
            $cmd3.SetGuidValue([ref](MakeKey 10), [ref]$CMD_MTP)
            $cmd3.SetUnsignedIntegerValue([ref](MakeKey 11), [uint32]0)
            $cmd3.SetUnsignedIntegerValue([ref](MakeKey 0), [uint32]0x9180)
            $results3 = $null
            $content.SendCommand([uint32]0, $cmd3, [ref]$results3)

            # Try to read data
            $dataKey = MakeKey 4
            $dataObj = $null
            try { $results3.GetValue([ref]$dataKey, [ref]$dataObj) } catch {}

            try { $dev.Close() } catch {}

            if ($dataObj -is [byte[]] -and $dataObj.Length -gt 0) {
                $b64 = [Convert]::ToBase64String($dataObj)
                @{ success = $true; base64 = $b64; size = $dataObj.Length } | ConvertTo-Json -Compress
            } else {
                @{ success = $false; error = "No live view data" } | ConvertTo-Json -Compress
            }
        } catch {
            @{ success = $false; error = "$_" } | ConvertTo-Json -Compress
        }
    }

    "capture" {
        @{ success = $false; error = "Canon capture not yet implemented" } | ConvertTo-Json -Compress
    }

    default {
        @{ success = $false; error = "Unknown action: $Action" } | ConvertTo-Json -Compress
    }
}
