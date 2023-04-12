# Versiojulkaisu

Kehitys- ja QA-ympäristöjen versiojulkaisu on automatisoitu AWS:n CodePipelinellä.   
Tuotantoympäristön päivityksessä tarvitaan pilvioperaattorin apua, koska tuotanto on ns. Full service -sopimuksella, eikä Viite-kehitystiimillä ei ole siten tuotantoympäristöön kaikkia oikeuksia.

### Kehitysympäristön versiojulkaisu ###
CodePipeline kuuntelee Viiteen Gitin kehitysbranchiin (2023-03 tämä on nimeltään `postgis`) pushattuja commiteja, 
ja alkaa sen jälkeen automaattisesti buildata sen perusteella kehitysversiota CodeBuildilla. ("Push_to_postgis" -Build projekti.)    
Buildaus tapahtuu  `buildspec.yaml`in mukaisesti; buildi asentelee konttiin ns. kaiken alusta asti ja ajaa testit ennen kuin uusi commit päästetään eteenpäin. 
Onnistuneiden testien ja käännon jälkeen AWS nostaa uuden development-ympäristön pystyyn ja nitistää vanhat alta pois.   
Jos buildi törmää virheeseen, tulee AWS/SNS:n toimesta sähköposti niille, jotka ovat rekisteröityneet kuuntelemaan `viite-codebuild-status-email-topic`-viestejä.

Tuorein kehitysympäristöversio löytyy operaation päätteeksi internetitse osoitteesta `https://viitedev.testivaylapilvi.fi/`.

### QA-ympäristön versiojulkaisu ###
QA-ympäristöön julkaisu tapahtuu kehitysympäristöä vastaavasti,
Viiteen Gitin QA-branchiin (2023-03 tämä on nimeltään `NextRelease`, ja se on olemassa vain tarpeen mukaan) pushattuja commiteja.
Buildiprosesi menee vastaavasti kuin kehitysympäristössä.

### Tuotantoympäristön versiojulkaisu ###
Ensin tiimi puskee tuotannon AWS/ECR-repoon uuden, QA-ympäristössä jo luodun, kontin.
Sen jälkeen tarvitaan vielä servicen päivitys, jonka hoitaa pilvioperaattori.
Miten - tarkempia ohjeita: `viite\aws\cloud-formation\prod\README.md`.

### Tekniset ohjeet ###
`viite\aws\cloud-formation]\<ympäristö>\README.md`.


## Postgis-kantojen alustus ja päivitys

Olemassaolevat dev, QA ja prod toki riittänevät pitkälle, mutta uusia kantoja voi kysellä tarvittaessa pilvioperaattorilta.   
He alustavat tarvittaessa Postgis-tietokannan ja skeemat sinne, minne tiimillä ei ole valtuuksia tietokantoja luoda.

Viite:n Postgis-kanta on versioitu.
Tietokannan rakenne päivitetään automaattisesti buildauksien yhteydessä (ks. `buildspec.yaml`).

Automaattipäivitys on toteutettu [Flyway](http://flywaydb.org/)-tietokantamigraatiotyökalulla.
Tietokantamigraatiomääritykset on versioitu `db.migration`-paketissa `digiroad2-oracle`-projektissa.
Flyway päivittää tiedon käytössä olevasta migraatioversiosta tietokantaan itseensä; versiotieto löytyy taulusta `schema_version`.

Katso lisätietoja [Digiroad-2](README.md) artikkelista.

