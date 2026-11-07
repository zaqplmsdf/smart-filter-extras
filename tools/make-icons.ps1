# Regenerates the mod's GUI sprites and logo as pixel art (no external assets).
# Usage: powershell -ExecutionPolicy Bypass -File tools\make-icons.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$spriteDir = Join-Path $root 'src\main\resources\assets\smart_filter_extras\textures\gui\sprites\icon'
New-Item -ItemType Directory -Force -Path $spriteDir | Out-Null

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($x = 0; $x -lt $w; $x++) { for ($y = 0; $y -lt $h; $y++) { $bmp.SetPixel($x, $y, [System.Drawing.Color]::Transparent) } }
    return $bmp
}
function C([string]$hex) { return [System.Drawing.ColorTranslator]::FromHtml($hex) }
function Fill($bmp, [int]$x0, [int]$y0, [int]$x1, [int]$y1, $color) {
    for ($x = $x0; $x -le $x1; $x++) { for ($y = $y0; $y -le $y1; $y++) { $bmp.SetPixel($x, $y, $color) } }
}
function Box($bmp, [int]$x0, [int]$y0, [int]$x1, [int]$y1, $outline, $fill) {
    Fill $bmp $x0 $y0 $x1 $y1 $outline
    if ($x1 - $x0 -ge 2 -and $y1 - $y0 -ge 2) { Fill $bmp ($x0 + 1) ($y0 + 1) ($x1 - 1) ($y1 - 1) $fill }
}
function Pixels($bmp, [string[]]$rows, [hashtable]$palette, [int]$ox = 0, [int]$oy = 0) {
    for ($y = 0; $y -lt $rows.Length; $y++) {
        for ($x = 0; $x -lt $rows[$y].Length; $x++) {
            $ch = [string]$rows[$y][$x]
            if ($palette.ContainsKey($ch)) { $bmp.SetPixel($ox + $x, $oy + $y, $palette[$ch]) }
        }
    }
}
function Save($bmp, [string]$path) { $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose() }

$outline = C '#3A3A3A'
$paper = C '#F4F4F4'
$line = C '#9E9E9E'

# copy: two overlapping sheets
$b = New-Canvas 12 12
Box $b 0 0 7 7 $outline $paper
Fill $b 2 2 5 2 $line
Box $b 4 3 11 11 $outline $paper
Fill $b 6 5 9 5 $line; Fill $b 6 7 9 7 $line; Fill $b 6 9 8 9 $line
Save $b (Join-Path $spriteDir 'copy.png')

# paste: clipboard with a sheet
$b = New-Canvas 12 12
Box $b 1 1 10 11 $outline (C '#A8743A')
Box $b 3 3 8 10 $outline $paper
Fill $b 4 5 7 5 $line; Fill $b 4 7 7 7 $line
Box $b 4 0 7 2 $outline (C '#C9C9C9')
Save $b (Join-Path $spriteDir 'paste.png')

# view_all: 3x3 grid of items
$b = New-Canvas 12 12
foreach ($gx in 0, 4, 8) { foreach ($gy in 0, 4, 8) { Box $b (1 + $gx - 1 + 1) (1 + $gy - 1 + 1) (3 + $gx) (3 + $gy) $outline (C '#8B8B8B') } }
Save $b (Join-Path $spriteDir 'view_all.png')

# view_selected: green check mark
$b = New-Canvas 12 12
$check = @(
    '..........oo',
    '.........ogo',
    '........oggo',
    '.......oggo.',
    'oo....oggo..',
    'ogo..oggo...',
    'oggoogg o...'.Replace(' ', 'o'),
    '.oggggo.....',
    '..oggo......',
    '...oo.......'
)
Pixels $b $check @{ 'o' = (C '#1F4F1F'); 'g' = (C '#4CC24C') } 0 1
Save $b (Join-Path $spriteDir 'view_selected.png')

# check_small: 7x6 badge drawn on selected grid slots
$b = New-Canvas 7 6
$small = @(
    '.....oo',
    '....ogo',
    'oo.ogo.',
    'ogogo..',
    '.ogo...',
    '..o....'
)
Pixels $b $small @{ 'o' = (C '#1F4F1F'); 'g' = (C '#5EE05E') }
Save $b (Join-Path $spriteDir 'check_small.png')

# variants: a potion bottle (toggle "tell variants apart")
$b = New-Canvas 12 12
$bottle = @(
    '....oooo....',
    '....oCCo....',
    '....oGGo....',
    '...oGGGGo...',
    '..oGGGGGGo..',
    '.oPPPPPPPPo.',
    '.oPPWPPPPPo.',
    '.oPWPPPPPPo.',
    '.oPPPPPPPPo.',
    '..oPPPPPPo..',
    '...oooooo...'
)
Pixels $b $bottle @{ 'o' = $outline; 'C' = (C '#9C6B3A'); 'G' = (C '#D8E4F0'); 'P' = (C '#C2468E'); 'W' = (C '#F3B6D8') } 0 0
Save $b (Join-Path $spriteDir 'variants.png')

# trash: clear selection
$b = New-Canvas 12 12
$trash = @(
    '....oooo....',
    '.oooooooooo.',
    '.oLLLLLLLLo.',
    '.oooooooooo.',
    '..oGGGGGGo..',
    '..oGoGoGoG..'.Replace('oG..', 'oo..'),
    '..oGoGoGGo..',
    '..oGoGoGGo..',
    '..oGoGoGGo..',
    '..oGGGGGGo..',
    '...oooooo...'
)
Pixels $b $trash @{ 'o' = $outline; 'L' = (C '#D0D0D0'); 'G' = (C '#B4B4B4') } 0 0
Save $b (Join-Path $spriteDir 'trash.png')

# arrows for paging (5x9)
$b = New-Canvas 5 9
Pixels $b @('o....', 'oo...', 'oWo..', 'oWWo.', 'oWWWo', 'oWWo.', 'oWo..', 'oo...', 'o....') @{ 'o' = $outline; 'W' = (C '#F0F0F0') } 0 0
Save $b (Join-Path $spriteDir 'arrow_right.png')
$b = New-Canvas 5 9
Pixels $b @('....o', '...oo', '..oWo', '.oWWo', 'oWWWo', '.oWWo', '..oWo', '...oo', '....o') @{ 'o' = $outline; 'W' = (C '#F0F0F0') } 0 0
Save $b (Join-Path $spriteDir 'arrow_left.png')

# warning triangle (missing IDs)
$b = New-Canvas 12 12
$warn = @(
    '.....oo.....',
    '....oYYo....',
    '....oYYo....',
    '...oYooYo...',
    '...oYooYo...',
    '..oYYooYYo..',
    '..oYYooYYo..',
    '.oYYYYYYYYo.',
    '.oYYYooYYYo.',
    'oYYYYooYYYYo',
    'oYYYYYYYYYYo',
    'oooooooooooo'
)
Pixels $b $warn @{ 'o' = (C '#5A3A00'); 'Y' = (C '#F2C230') } 0 0
Save $b (Join-Path $spriteDir 'warning.png')

# clear_small: x inside the search field (7x7)
$b = New-Canvas 7 7
Pixels $b @('x.....x', '.x...x.', '..x.x..', '...x...', '..x.x..', '.x...x.', 'x.....x') @{ 'x' = (C '#BDBDBD') } 0 0
Save $b (Join-Path $spriteDir 'clear_small.png')

# logo: 32x32 pixel art scaled to 128x128 - a funnel sorting three items, with a check mark
$s = New-Canvas 32 32
$bg = C '#2B2F3A'
Fill $s 0 0 31 31 $bg
Box $s 0 0 31 31 (C '#171A21') $bg
# items falling in
Box $s 6 3 11 8 (C '#2E1F0F') (C '#8A5A2B')      # wood
Box $s 13 1 18 6 (C '#1C3D1C') (C '#56B04A')     # leaf/green
Box $s 20 3 25 8 (C '#1D2E4A') (C '#5A8FD8')     # blue
Fill $s 7 4 8 4 (C '#B07A42'); Fill $s 14 2 15 2 (C '#8EE07A'); Fill $s 21 4 22 4 (C '#9CC4F5')
# funnel
$funnel = @(
    'oooooooooooooooooooooooo',
    'oWWWWWWWWWWWWWWWWWWWWWWo',
    '.oSSSSSSSSSSSSSSSSSSSSo.',
    '..oSSSSSSSSSSSSSSSSSSo..',
    '...oSSSSSSSSSSSSSSSSo...',
    '....oSSSSSSSSSSSSSSo....',
    '.....oSSSSSSSSSSSSo.....',
    '......oSSSSSSSSSSo......',
    '.......oSSSSSSSSo.......',
    '........oSSSSSSo........',
    '.........oSSSSo.........',
    '.........oSSSSo.........',
    '.........oSSSSo.........',
    '.........oSSSSo.........',
    '.........oooooo.........'
)
Pixels $s $funnel @{ 'o' = (C '#14161C'); 'W' = (C '#E6E9EF'); 'S' = (C '#9AA3B5') } 4 10
# highlight stripe on the funnel
for ($i = 0; $i -lt 8; $i++) { $s.SetPixel(7 + $i, 12 + $i, (C '#C3CAD8')) }
# selected item coming out + check mark badge
Box $s 13 26 18 30 (C '#1C3D1C') (C '#56B04A')
$badge = @(
    '.....oo',
    '....ogo',
    'oo.ogo.',
    'ogogo..',
    '.ogo...',
    '..o....'
)
Box $s 22 22 30 30 (C '#14161C') (C '#F4F4F4')
Pixels $s $badge @{ 'o' = (C '#1F4F1F'); 'g' = (C '#3FB43F') } 23 23
$logo = New-Object System.Drawing.Bitmap 128, 128
$g = [System.Drawing.Graphics]::FromImage($logo)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.DrawImage($s, 0, 0, 128, 128)
$g.Dispose(); $s.Dispose()
Save $logo (Join-Path $root 'src\main\resources\smart_filter_extras_logo.png')

Write-Host "Icons written to $spriteDir and the logo to src\main\resources"
