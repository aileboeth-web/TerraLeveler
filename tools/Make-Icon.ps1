param([string]$Out = (Join-Path (Split-Path -Parent $PSScriptRoot) 'docs\icon\terraleveler-icon.png'))
Add-Type -AssemblyName System.Drawing
$N = 32; $Scale = 4
$bmp = New-Object System.Drawing.Bitmap ($N * $Scale), ($N * $Scale)
function C($hex, $a = 255) { $c = [System.Drawing.ColorTranslator]::FromHtml($hex); [System.Drawing.Color]::FromArgb($a, $c.R, $c.G, $c.B) }
function Mix($c1, $c2, [double]$t) {
    [System.Drawing.Color]::FromArgb(255, [int]($c1.R + ($c2.R - $c1.R) * $t), [int]($c1.G + ($c2.G - $c1.G) * $t), [int]($c1.B + ($c2.B - $c1.B) * $t))
}
$skyTop = C '#4FA3E8'; $skyBottom = C '#B9E3FF'
$grass = C '#5DAE36'; $grassLight = C '#86D04F'; $grassDark = C '#3F8A24'
$dirt = C '#8A5A2E'; $dirtLight = C '#A06C3B'; $dirtDark = C '#6B4422'
$stone = C '#808080'; $stoneDark = C '#5E5E5E'
$cut = C '#FFD43B'; $cutDark = C '#E0A800'
$ground = 17                                  # target ground surface row (grass)
# hill heights above the ground for columns 0..15 (stepped like Minecraft terrain)
$hill = @(6, 8, 10, 11, 12, 12, 11, 11, 10, 9, 7, 6, 4, 3, 2, 1)
$rand = New-Object System.Random 7
$px = New-Object 'System.Drawing.Color[,]' $N, $N
for ($y = 0; $y -lt $N; $y++) {
    for ($x = 0; $x -lt $N; $x++) {
        $top = $ground
        if ($x -lt $hill.Length) { $top = $ground - $hill[$x] }
        if ($y -lt $top) {
            $col = Mix $skyTop $skyBottom ($y / [double]$ground)
        } elseif ($y -eq $top) {
            $col = if (($x + $y) % 5 -eq 0) { $grassLight } elseif (($x * 3 + $y) % 7 -eq 0) { $grassDark } else { $grass }
        } elseif ($y -eq $top + 1 -and ($x % 3 -ne 1)) {
            $col = $grassDark
        } else {
            $r = $rand.Next(100)
            if ($y -ge 25 -and $r -lt 22) { $col = if ($r -lt 8) { $stoneDark } else { $stone } }
            elseif ($r -lt 18) { $col = $dirtLight } elseif ($r -lt 30) { $col = $dirtDark } else { $col = $dirt }
        }
        # the part of the hill above the target ground is about to be cut: faded, hatched
        if ($y -lt $ground -and $y -ge $top) {
            $sky = Mix $skyTop $skyBottom ($y / [double]$ground)
            $t = if ((($x + $y) % 4) -lt 2) { 0.42 } else { 0.12 }
            $col = Mix $col $sky $t
        }
        $px[$x, $y] = $col
    }
}
# dashed cutting line just above the target ground, across the hill and a little beyond
for ($x = 0; $x -lt 20; $x++) {
    if (($x % 4) -lt 3) { $px[$x, ($ground - 1)] = if ($x % 4 -eq 0) { $cutDark } else { $cut } }
}
# small arrow pointing down onto the cut line above the hill
foreach ($p in @(@(6, 2), @(6, 3), @(6, 4), @(5, 3), @(7, 3), @(4, 2), @(8, 2))) { }
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
for ($y = 0; $y -lt $N; $y++) {
    for ($x = 0; $x -lt $N; $x++) {
        $brush = New-Object System.Drawing.SolidBrush $px[$x, $y]
        $g.FillRectangle($brush, $x * $Scale, $y * $Scale, $Scale, $Scale)
        $brush.Dispose()
    }
}
$g.Dispose()
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"saved $Out"
