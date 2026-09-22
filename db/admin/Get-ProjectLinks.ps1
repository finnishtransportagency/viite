<#
.SYNOPSIS
    Query project_link rows for a given project name from the local viite database.
.PARAMETER ProjectName
    Project name to search for (case-insensitive, partial match).
.PARAMETER Csv
    Output results as CSV instead of aligned table.
.EXAMPLE
    .\Get-ProjectLinks.ps1 "Vt4 parannus"
    .\Get-ProjectLinks.ps1 "Vt4" -Csv
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$ProjectName,

    [switch]$Csv
)

$container = 'postgis-viite'

$running = docker inspect --format "{{.State.Running}}" $container 2>$null
if ($LASTEXITCODE -ne 0 -or $running -ne 'true') {
    Write-Error "Container '$container' is not running. Start it with:`n  docker compose -f local-dev/postgis/docker-compose.yaml up -d"
    exit 1
}

# Escape single quotes so project names with apostrophes don't break the query
$safeName = $ProjectName -replace "'", "''"

$sql = @"
SELECT
  p.name                                               AS project_name,
  pl.road_number,
  pl.road_part_number,
  CASE pl.track
    WHEN 0  THEN '0-Yhdistetty'  WHEN 1  THEN '1-Oikea'
    WHEN 2  THEN '2-Vasen'       WHEN 99 THEN '99-Tuntematon'
    ELSE pl.track::text END                            AS track,
  pl.start_addr_m,
  pl.end_addr_m,
  pl.original_start_addr_m,
  pl.original_end_addr_m,
  CASE pl.status
    WHEN 0  THEN '0-Kasittelematon'  WHEN 1  THEN '1-Ennallaan'
    WHEN 2  THEN '2-Uusi'            WHEN 3  THEN '3-Siirto'
    WHEN 4  THEN '4-Numerointi'      WHEN 5  THEN '5-Lakkautus'
    WHEN 99 THEN '99-Tuntematon'
    ELSE pl.status::text END                           AS status,
  CASE pl.discontinuity_type
    WHEN 1 THEN '1-Tien loppu'        WHEN 2 THEN '2-Epajatkuva'
    WHEN 3 THEN '3-EVK-raja'          WHEN 4 THEN '4-Lieva epajatkuvuus'
    WHEN 5 THEN '5-Jatkuva'
    ELSE pl.discontinuity_type::text END               AS discontinuity,
  pl.link_id,
  pl.start_measure,
  pl.end_measure,
  pl.reversed,
  pl.created_by,
  pl.modified_by,
  pl.modified_date
FROM project_link pl
JOIN project p ON p.id = pl.project_id
WHERE p.name ILIKE '%$safeName%'
ORDER BY pl.road_number, pl.road_part_number, pl.track, pl.start_addr_m;
"@

if ($Csv) {
    $sql | docker exec -i $container psql -U viite -d viite --csv
} else {
    $sql | docker exec -i $container psql -U viite -d viite
}
