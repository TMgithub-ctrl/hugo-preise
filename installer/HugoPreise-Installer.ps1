# Hugo Preise - Installer
# Laedt die neueste Version der Mod von GitHub und legt sie in den gewaehlten Minecraft-mods-Ordner.
# Optional wird die Fabric API fuer Minecraft 26.2 von Modrinth mitgeladen.

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo        = "TMgithub-ctrl/hugo-preise"
$McVersion   = "26.2"
$UserAgent   = "HugoPreise-Installer"
$ModPrefix   = "cripzys-hugo-preise-"

# ---------- Minecraft-Ordner finden ----------
function Find-ModFolders {
    $found = New-Object System.Collections.Generic.List[object]
    $mc = Join-Path $env:APPDATA ".minecraft"
    if (Test-Path -LiteralPath $mc) {
        $found.Add([pscustomobject]@{ Label = "Minecraft Launcher (.minecraft)"; Path = (Join-Path $mc "mods") })
    }

    $roots = @(
        @{ Name = "CurseForge"; Path = (Join-Path $env:USERPROFILE "curseforge\minecraft\Instances"); Sub = "mods" },
        @{ Name = "Modrinth";   Path = (Join-Path $env:APPDATA "ModrinthApp\profiles");              Sub = "mods" },
        @{ Name = "Prism";      Path = (Join-Path $env:APPDATA "PrismLauncher\instances");           Sub = "minecraft\mods" },
        @{ Name = "Prism";      Path = (Join-Path $env:APPDATA "PrismLauncher\instances");           Sub = ".minecraft\mods" },
        @{ Name = "MultiMC";    Path = (Join-Path $env:APPDATA "MultiMC\instances");                 Sub = ".minecraft\mods" }
    )
    foreach ($r in $roots) {
        if (-not (Test-Path -LiteralPath $r.Path)) { continue }
        Get-ChildItem -LiteralPath $r.Path -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $mods = Join-Path $_.FullName $r.Sub
            if (Test-Path -LiteralPath $mods) {
                $found.Add([pscustomobject]@{ Label = "$($r.Name): $($_.Name)"; Path = $mods })
            }
        }
    }
    return $found
}

# ---------- Downloads ----------
function Get-LatestModInfo {
    $api = "https://api.github.com/repos/$Repo/contents/download"
    $items = Invoke-RestMethod -Uri $api -Headers @{ "User-Agent" = $UserAgent } -UseBasicParsing
    $jar = $items | Where-Object { $_.name -like "$ModPrefix*.jar" -and $_.name -notlike "*-sources*" } | Select-Object -First 1
    if (-not $jar) { throw "Keine Mod-Datei auf GitHub gefunden." }
    return $jar
}

function Get-FabricApiInfo {
    $q = "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22$McVersion%22%5D&loaders=%5B%22fabric%22%5D"
    $versions = Invoke-RestMethod -Uri $q -Headers @{ "User-Agent" = $UserAgent } -UseBasicParsing
    if (-not $versions -or $versions.Count -eq 0) { throw "Keine Fabric API fuer $McVersion gefunden." }
    $file = $versions[0].files | Where-Object { $_.primary } | Select-Object -First 1
    if (-not $file) { $file = $versions[0].files[0] }
    return $file
}

function Save-Download($url, $target) {
    $tmp = "$target.download"
    Invoke-WebRequest -Uri $url -OutFile $tmp -Headers @{ "User-Agent" = $UserAgent } -UseBasicParsing
    if ((Get-Item -LiteralPath $tmp).Length -lt 1000) { Remove-Item -LiteralPath $tmp -Force; throw "Download unvollstaendig: $url" }
    Move-Item -LiteralPath $tmp -Destination $target -Force
}

# ---------- Fenster ----------
$form = New-Object System.Windows.Forms.Form
$form.Text = "Hugo Preise - Installer"
$form.Size = New-Object System.Drawing.Size(560, 380)
$form.StartPosition = "CenterScreen"
$form.FormBorderStyle = "FixedDialog"
$form.MaximizeBox = $false
$form.Font = New-Object System.Drawing.Font("Segoe UI", 9)

$title = New-Object System.Windows.Forms.Label
$title.Text = "Hugo Preise fuer HugoSMP installieren"
$title.Font = New-Object System.Drawing.Font("Segoe UI", 13, [System.Drawing.FontStyle]::Bold)
$title.Location = New-Object System.Drawing.Point(16, 12)
$title.AutoSize = $true
$form.Controls.Add($title)

$info = New-Object System.Windows.Forms.Label
$info.Text = "Benoetigt: Minecraft $McVersion mit Fabric Loader. Waehle den mods-Ordner deiner Fabric-Installation."
$info.Location = New-Object System.Drawing.Point(18, 44)
$info.Size = New-Object System.Drawing.Size(510, 34)
$form.Controls.Add($info)

$combo = New-Object System.Windows.Forms.ComboBox
$combo.DropDownStyle = "DropDownList"
$combo.Location = New-Object System.Drawing.Point(18, 82)
$combo.Size = New-Object System.Drawing.Size(410, 24)
$form.Controls.Add($combo)

$browse = New-Object System.Windows.Forms.Button
$browse.Text = "Anderer Ordner..."
$browse.Location = New-Object System.Drawing.Point(436, 80)
$browse.Size = New-Object System.Drawing.Size(92, 27)
$form.Controls.Add($browse)

$pathLabel = New-Object System.Windows.Forms.Label
$pathLabel.Location = New-Object System.Drawing.Point(18, 112)
$pathLabel.Size = New-Object System.Drawing.Size(510, 18)
$pathLabel.ForeColor = [System.Drawing.Color]::DimGray
$form.Controls.Add($pathLabel)

$chkApi = New-Object System.Windows.Forms.CheckBox
$chkApi.Text = "Fabric API mitinstallieren, falls sie fehlt"
$chkApi.Checked = $true
$chkApi.Location = New-Object System.Drawing.Point(18, 138)
$chkApi.AutoSize = $true
$form.Controls.Add($chkApi)

$chkOld = New-Object System.Windows.Forms.CheckBox
$chkOld.Text = "Alte Versionen von Hugo Preise deaktivieren"
$chkOld.Checked = $true
$chkOld.Location = New-Object System.Drawing.Point(18, 162)
$chkOld.AutoSize = $true
$form.Controls.Add($chkOld)

$log = New-Object System.Windows.Forms.TextBox
$log.Multiline = $true
$log.ReadOnly = $true
$log.ScrollBars = "Vertical"
$log.Location = New-Object System.Drawing.Point(18, 192)
$log.Size = New-Object System.Drawing.Size(510, 96)
$form.Controls.Add($log)

$install = New-Object System.Windows.Forms.Button
$install.Text = "Installieren"
$install.Font = New-Object System.Drawing.Font("Segoe UI", 10, [System.Drawing.FontStyle]::Bold)
$install.Location = New-Object System.Drawing.Point(318, 298)
$install.Size = New-Object System.Drawing.Size(120, 32)
$form.Controls.Add($install)

$close = New-Object System.Windows.Forms.Button
$close.Text = "Schliessen"
$close.Location = New-Object System.Drawing.Point(446, 298)
$close.Size = New-Object System.Drawing.Size(82, 32)
$close.Add_Click({ $form.Close() })
$form.Controls.Add($close)

function Write-Log($text) {
    $log.AppendText($text + [Environment]::NewLine)
    [System.Windows.Forms.Application]::DoEvents()
}

$folders = New-Object System.Collections.Generic.List[object]
foreach ($f in (Find-ModFolders)) { $folders.Add($f) ; [void]$combo.Items.Add($f.Label) }
if ($folders.Count -gt 0) { $combo.SelectedIndex = 0 } else { $pathLabel.Text = "Kein Minecraft-Ordner gefunden - bitte 'Anderer Ordner...' waehlen." }

$combo.Add_SelectedIndexChanged({
    if ($combo.SelectedIndex -ge 0) { $pathLabel.Text = $folders[$combo.SelectedIndex].Path }
})
if ($combo.SelectedIndex -ge 0) { $pathLabel.Text = $folders[0].Path }

$browse.Add_Click({
    $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
    $dlg.Description = "mods-Ordner deiner Fabric-Installation waehlen"
    if ($dlg.ShowDialog() -eq "OK") {
        $folders.Add([pscustomobject]@{ Label = "Eigener Ordner: $($dlg.SelectedPath)"; Path = $dlg.SelectedPath })
        [void]$combo.Items.Add("Eigener Ordner: $($dlg.SelectedPath)")
        $combo.SelectedIndex = $combo.Items.Count - 1
    }
})

$install.Add_Click({
    if ($combo.SelectedIndex -lt 0) { [System.Windows.Forms.MessageBox]::Show("Bitte zuerst einen Ordner waehlen.") | Out-Null; return }
    $mods = $folders[$combo.SelectedIndex].Path
    $install.Enabled = $false
    $log.Clear()
    try {
        if (-not (Test-Path -LiteralPath $mods)) { New-Item -ItemType Directory -Path $mods | Out-Null; Write-Log "Ordner angelegt: $mods" }

        Write-Log "Suche neueste Version auf GitHub ..."
        $jar = Get-LatestModInfo
        Write-Log "Gefunden: $($jar.name)"

        if ($chkOld.Checked) {
            Get-ChildItem -LiteralPath $mods -Filter "$ModPrefix*.jar" | Where-Object { $_.Name -ne $jar.name } | ForEach-Object {
                Move-Item -LiteralPath $_.FullName -Destination ($_.FullName + ".disabled") -Force
                Write-Log "Alte Version deaktiviert: $($_.Name)"
            }
        }

        Save-Download $jar.download_url (Join-Path $mods $jar.name)
        Write-Log "Installiert: $($jar.name)"

        $hasApi = Get-ChildItem -LiteralPath $mods -Filter "fabric-api*.jar" -ErrorAction SilentlyContinue
        if ($hasApi) {
            Write-Log "Fabric API ist schon da: $($hasApi[0].Name)"
        } elseif ($chkApi.Checked) {
            Write-Log "Lade Fabric API fuer $McVersion ..."
            $api = Get-FabricApiInfo
            Save-Download $api.url (Join-Path $mods $api.filename)
            Write-Log "Installiert: $($api.filename)"
        } else {
            Write-Log "ACHTUNG: Fabric API fehlt - ohne sie startet die Mod nicht."
        }

        Write-Log ""
        Write-Log "Fertig! Minecraft $McVersion mit Fabric starten."
        [System.Windows.Forms.MessageBox]::Show("Hugo Preise ist installiert.`n`nStarte Minecraft $McVersion mit Fabric.", "Fertig") | Out-Null
    } catch {
        Write-Log "FEHLER: $($_.Exception.Message)"
        [System.Windows.Forms.MessageBox]::Show("Installation fehlgeschlagen:`n$($_.Exception.Message)", "Fehler") | Out-Null
    } finally {
        $install.Enabled = $true
    }
})

[void]$form.ShowDialog()
