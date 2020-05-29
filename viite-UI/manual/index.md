Viite-sovelluksen käyttöohje
======================================================
Viite
-----------------------

Viite on Väylän tieosoitejärjestelmän ylläpitosovellus. Viitteellä hallitaan tieosoitejärjestelmän muutoksia ja se tarjoaa ajantasaisen kuvauksen tiestöstä Digiroadin (VVH:n) ajantasaisella linkkigeometrialla.

Linkistä https://testiextranet.vayla.fi/extranet/web/fi/viite?kategoria=7457637 (testi) pääsee Väylän extranetin Viite-sivulle (tällä hetkellä testiextranet käytössä, varsinainen extranet-osoite päivitetään myöhemmin), jossa kerrotaan Viitteen yleiskuvaus ja annetaan tiedotteita käyttäjille. Sivulla ylläpidetään myös dokumentaatiota Viitteestä. 

__Huom! Suosittelemme käyttämään selaimena Firefoxia tai Chromea.__

__Huom! Käyttöohjeen kuvia voi klikata isommaksi, jolloin tekstit erottuvat paremmin.__

# 1. Miten päästä alkuun
-----------------------

Viite-sovelluksen käyttöä varten tarvitaan Väylän tunnukset (A-, U-, LX-, K- tai L-alkuinen). Mikäli sinulla ei ole Väylän tunnuksia, pyydä ne yhteyshenkilöltäsi Väylästä.

Kaikilla Väylän tunnuksilla on pääsy Viite-sovellukseen katselukäyttäjänä.

Viite-sovellukseen kirjaudutaan osoitteessa: <a href="https://extranet.vayla.fi/viite/" target="_blank">https://extranet.vayla.fi/viite/</a>.

![Kirjautuminen Viite-sovellukseen.](k1.jpg)

_Kuva 1.1: Kirjautuminen Viite-sovellukseen._

Kirjautumisen jälkeen avautuu karttakäyttöliittymässä katselutila.

![Näkymä kirjautumisen jälkeen.](k2.jpg)

_Kuva 1.2: Karttanäkymä kirjautumisen jälkeen._

Oikeudet on rajattu käyttäjän roolin mukaan:

- Ilman erikseen annettuja oikeuksia Väylän tunnuksilla pääsee katselemaan kaikkia tieosoitteita
- Tieosoiteprojektit, Tiennimen ylläpito sekä Solmut ja liittymät -painikkeet näkyvät vain käyttäjille, joilla on oikeudet muokata tieosoitteita

Jos kirjautumisen jälkeen ei avaudu karttakäyttöliittymän katselutilaa, ei kyseisellä tunnuksella ole pääsyä Väylän extranettiin. Tällöin tulee ottaa yhteyttä Väylässä tai ELYssä omaan yhteyshenkilöön.

1.1 Mistä saada opastusta
--------------------------

Viite-sovelluksen käytössä avustaa Janne Grekula, janne.grekula@cgi.com.

#### Ongelmatilanteet

Sovelluksen toimiessa virheellisesti (esim. kaikki aineistot eivät lataudu oikein), menettele seuraavasti:

- Lataa sivu uudelleen näppäimistön F5-painikkeella
- Tarkista, että selaimestasi on käytössä ajan tasalla oleva versio ja selaimesi on Mozilla Firefox tai Chrome
- Jos edellä olevat eivät korjaa ongelmaa, ota yhteyttä janne.grekula@cgi.com

Tämä ohje käsittelee pääasiassa vain Viite-sovelluksen käyttöä, ei niinkään tieosoitejärjestelmää. Tarkemmat tiedot tieosoitejärjestelmästä löydät täältä: https://vayla.fi/documents/20473/143621/tieosoitejärjestelmä.pdf.

# 2. Perustietoja Viite-sovelluksesta
--------------------------

2.1 Tiedon rakentuminen Viite-sovelluksessa
--------------------------

Viite-sovelluksessa tieosoiteverkko piirretään VVH:n tarjoaman Maanmittauslaitoksen keskilinja-aineiston päälle. Maanmittauslaitoksen keskilinja-aineisto muodostuu tielinkeistä. Tielinkki on tien, kadun, kevyen liikenteen väylän tai lauttayhteyden keskilinjageometrian pienin yksikkö. Tieosoiteverkko piirtyy geometrian päälle tieosoitesegmentteinä _lineaarisen referoinnin_ avulla. 

Tielinkki on Viite-sovelluksen lineaarinen viitekehys, jonka geometriaan sidotaan tieosoitesegmentit. Kukin tieosoitesegmentti tietää, mille tielinkille se kuuluu (tielinkin ID), sekä kohdan, josta se alkaa ja loppuu kyseisellä tielinkillä. Tieosoitesegmentit ovat siten tielinkin mittaisia tai niitä lyhyempiä tieosoitteen osuuksia. Käyttöliittymässä kuitenkin pienin valittavissa oleva osuus on tielinkin mittainen (ks. luvut 5.1 ja 7.1).

Kullakin tieosoitesegmentillä on lisäksi tiettyjä sille annettuja ominaisuustietoja, kuten tienumero, tieosanumero ja ajoratakoodi. Tieosoitesegmenttien ominaisuustiedoista on kerrottu tarkemmin luvussa 5.2.

![Kohteita](k9.jpg)

_Kuva 2.1: Tieosoitesegmenttejä (1) ja muita tielinkkejä (2) Viitteen karttaikkunassa._

Tieosoitesegmentit piirretään Viite-sovelluksessa kartalle erilaisin värein (ks. luku 5). Muut tielinkit, jotka eivät kuulu tieosoiteverkkoon, piirretään kartalle harmaalla. Näitä ovat esimerkiksi tieosoitteettomat kuntien omistamat tiet, ajopolut tai ajotiet.

Palautteet geometrian/tielinkkien virheistä voi laittaa Maanmittauslaitokselle, maasto@maanmittauslaitos.fi. Mukaan liitetään selvitys virheestä ja sen sijainnista (esim. kuvakaappaus).

# 3. Automatiikka Viite-sovelluksessa
--------------------------
Viite-sovelluksessa on muutamia automatiikan tekemiä yleistyksiä tai korjauksia. Automatiikka ei muuta mitään sellaisia tietoja, jotka muuttaisivat varsinaisesti tieosoitteita. Automatiikan tekemät muutokset liittyvät siihen, että tieosoiteverkkoa ylläpidetään keskilinjageometrian päällä, ja tuon keskilinjageometrian ylläpidosta vastaa Maanmittauslaitos. Tietyt automaattiset toimenpiteet helpottavat tieosoiteverkon ylläpitäjää varsinaisessa tieosoiteverkon hallinnassa.

__Huom! Automatiikka ei koskaan muuta tieosan mitattua pituutta. Arvot tieosien ja ajoratojen vaihtumiskohdissa pysyvät aina ennallaan automatiikan tekemien korjausten yhteydessä.__

3.1 Tieosoitesegmenttien yhdistely tielinkin mittaisiksi osuuksiksi
--------------------------

Kun käyttäjä valitsee kartalla kohteita kaksoisklikkaamalla (ks. luku 5.1), on pienin valittava yksikkö tielinkin mittainen osuus tieosoiteverkosta, koska tielinkki on pienin mahdollinen yksikkö Maanmittauslaitoksen ylläpitämällä linkkiverkolla.

Tätä varten järjestelmä tekee automaattista yhdistelyä:

- Ne kohteet, joiden tielinkin ID, tie, tieosa, ajorata ja alkupäivämäärä ovat samoja (yhtenevä tieosoitehistoria), on yhdistetty tietokannassa yhdeksi tielinkin mittaiseksi tieosoitesegmentiksi
- Ne kohteet, joiden tielinkin ID, tie, tieosa, ajorata ovat samoja, mutta alkupäivämäärä ei ole sama (erilainen tieosoitehistoria), ovat tietokannassa edelleen erillisiä kohteita, mutta käyttöliittymässä ne ovat valittavissa vain tielinkin mittaisena osuutena. Tällä varmistetaan, että käyttöliittymä toimii käyttäjän kannalta loogisesti.  Tielinkin mittainen osuus on aina valittavissa, mutta tieosoitehistoria säilytetään tietokantatasolla.

3.2 Tieosoitesegmenttien automaattinen korjaus jatkuvasti päivittyvällä linkkigeometrialla
--------------------------

Viite-sovellus päivittää automaattisesti tieosoitesegmentit takaisin ajantasaiselle keskilinjalle, kun Maanmittauslaitos on tehnyt pieniä tarkennuksia keskilinjageometriaan. Tässä luvussa on kuvattu tapaukset, joissa Viite-sovellus osaa tehdä korjaukset automaattisesti. Ne tapaukset, joissa korjaus ei tapahdu automaattisesti, segmentit irtoavat geometriasta ja ne on korjattava manuaalisesti operaattorin toimesta.

Automatiikka tekee korjaukset, kun

1. __Tielinkki pitenee tai lyhenee alle metrin:__ Viite-sovellus lyhentää/pidentää tieosoitesegmenttiä automaattisesti muutoksen verran.
2. __Maanmittauslaitos yhdistelee tielinkkejä, esimerkiksi poistamalla tonttiliittymiä maanteiden varsilta:__ Tieosoitesegmentit siirretään uudelle geometrialle automaattisesti Väyläverkon hallinnan (VVH) tarjoaman tielinkkien muutosrajapinnan avulla.

# 4. Karttanäkymän toiminnot
--------------------------

![Karttanäkymän muokkaus](k3.jpg)

_Kuva 4.1: Karttanäkymä._

#### Kartan liikuttaminen

Karttaa liikutetaan raahaamalla pitämällä hiiren vasenta painiketta pohjassa ja liikuttamalla samalla hiirtä.

#### Hakukenttä

Käyttöliittymässä on hakukenttä (1), jossa voi hakea koordinaateilla ja katuosoitteella tai tieosoitteella. Haku suoritetaan kirjoittamalla hakuehto hakukenttään ja klikkaamalla Hae. Hakutulos tulee listaan hakukentän alle. Hakutuloslistassa ylimpänä on maantieteellisesti kartan nykyistä keskipistettä lähimpänä oleva kohde. Mikäli hakutuloksia on vain yksi, keskittyy kartta automaattisesti haettuun kohteeseen. Jos hakutuloksia on useampi kuin yksi, täytyy listalta valita tulos, johon kartta keskittyy. Tyhjennä tulokset -painike tyhjentää hakutuloslistan.

Tieosoitteella haku: Tieosoitteesta hakukenttään voi syöttää joko tienumeron tai tienumero + tieosanumeroyhdistelmän, esim. 2 tai 2 1. (Varsinainen tieosoitehaku tieosoitteiden ylläpidon tarpeisiin toteutetaan myöhemmin.)

Koordinaateilla haku: Koordinaatit syötetään muodossa "pohjoinen (7 merkkiä), itä (6 merkkiä)". Koordinaatit tulee olla ETRS89-TM35FIN -koordinaattijärjestelmässä, esim. 6975061, 535628.

Katuosoitteella haku: Katuosoitteesta hakukenttään voi syöttää koko osoitteen tai sen osan, esim. "Mannerheimintie" tai "Mannerheimintie 10, Helsinki".

#### Taustakartat

Taustakartaksi (2) voi valita vasemman alakulman painikkeista maastokartan, ortokuvat, taustakarttasarjan tai harmaasävykartan. Käytössä oleva harmaasävykartta ei tällä hetkellä ole kovin käyttökelpoinen.

#### Näytettävät tiedot

Käyttäjä voi halutessaan valita, näytetäänkö kartalla kiinteistörajat, Suravage-linkit tai tieosoiteverkko symboleineen (3). Valinnat saa päälle ja päältä pois valintaruutuja klikkaamalla. Näytä Suravage-linkit ja Näytä tieosoiteverkko -valinnat ovat automaattisesti päällä. Tieosoiteverkon symboleita ovat etäisyyslukemasymbolit ja suuntanuolet.

#### Mittakaavataso ja mittakaava

Käytössä oleva mittakaava näkyy kartan oikeassa alakulmassa (4).Kartan mittakaavatasoa muutetaan joko hiiren rullalla, kaksoisklikkaamalla, Shift+piirto -toiminnolla (alue) tai mittakaavapainikkeista (5). Mittakaavapainikkeita käyttämällä kartan keskitys säilyy. Hiiren rullalla, kaksoisklikkaamalla tai Shift+piirto -toimintoa käyttäen (alue) kartan keskitys siirtyy kohdistimen keskikohtaan.

#### Kohdistin

Kohdistin (6) kertoo kartan keskipisteen. Kohdistimen koordinaatit näkyvät karttaikkunan oikeassa alakulmassa (7). Kun karttaa liikuttaa, keskipiste muuttuu ja koordinaatit päivittyvät. Oikean alakulman valinnan (9) avulla kohdistimen saa myös halutessaan piilotettua kartalta.

#### Merkitse piste kartalla

Merkitse-painike (8) merkitsee sinisen pisteen kartan keskipisteeseen. Merkki poistuu vain, kun merkitään uusi piste kartalta.

# 5. Tieosoiteverkon katselu
--------------------------

Geometrialtaan yleistetty tieosoiteverkko tulee näkyviin, kun zoomaa tasolle, jonka mittakaavajanassa on lukema 5 km. Tästä tasosta ja sitä lähempää piirretään kartalle valtatiet, kantatiet, seututiet, yhdystiet ja numeroidut kadut. Yleistämätön tieverkko piirtyy mittakaavajanan lukemalla 2 km. 100 metriä (100 metrin mittakaavajanoja on kaksi kappaletta) suuremmilla mittakaavatasoilla tulevat näkyviin kaikki tieverkon kohteet.

Tieosoiteverkko on värikoodattu tienumeroiden mukaan. Vasemman yläkulman selitteessä on kerrottu kunkin värikoodin tienumerot. Lisäksi kartalle piirtyvät etäisyyslukemasymbolit, kohdat, joissa vaihtuu tieosa tai ajoratakoodi. Tieverkon kasvusuunta näkyy kartalla pisaran mallisena nuolena.

Kartalla näkyvä tieosoiteverkko on uusimman tieosoitteen mukainen verkko, jossa näkyvät tieosoitteiden viimeisimmät muutokset. Näiden muutosten alkupäivämäärä voi olla tulevaisuudessa, joten kartalla ei näy ns. tämän päivän tilanne.

![Mittakaavajanassa 2km](k4.jpg)

_Kuva 5.1: Tieosoiteverkon piirtyminen kartalle, kun mittakaavajanassa on 2 km._

![Mittakaavajanassa 100 m](k5.jpg)

_Kuva 5.2: Tieosoiteverkon piirtyminen kartalle, kun mittakaavajanassa on 100 m._

Tieosoitteelliset kadut erottuvat kartalla muista tieosoitesegmenteistä siten, että niiden ympärillä on musta reunaviiva.

![Tieosoitteellinen katu](k16.jpg)

_Kuva 5.3: Tieosoitteellinen katu, merkattuna mustalla reunaviivalla tienumeron värityksen lisäksi._

Kun hiiren vie tieosoiteverkon päälle, tulee kartalle näkyviin infolaatikko, joka kertoo kyseisen tieosoitesegmentin tienumeron, tieosanumeron, ajoratakoodin sekä alku- ja loppuetäisyyden.

![Hover](k35.jpg)

_Kuva 5.4: Infolaatikko, kun hiiri on viety tieosoitesegmentin päälle._

5.1 Kohteiden valinta
--------------------------
Kohteita voi valita kartalta klikkaamalla. Kertaklikkauksella sovellus valitsee kartalla näkyvästä tieosasta osuuden, jolla on sama tienumero, tieosanumero ja ajoratakoodi. Valittu tieosa korostuu kartalla (1), ja sen tiedot tulevat näkyviin karttaikkunan oikean reunan ominaisuustietonäkymään (2).

![Tieosuuden valinta](k6.jpg)

_Kuva 5.5: Tieosuuden valinta._

Kaksoisklikkaus valitsee yhden tielinkin mittaisen osuuden tieosoitteesta. Valittu osuus korostuu kartalla (3), ja sen tiedot tulevat näkyviin karttaikkunan oikean reunan ominaisuustietonäkymään (4).

![Tieosoitesegmentin valinta](k7.jpg)

_Kuva 5.6: Tielinkin mittaisen osuuden valinta._

5.2 Tieosoitteen ominaisuustiedot
--------------------------

Tieosoitteilla on seuraavat ominaisuustiedot:

|Ominaisuustieto|Kuvaus|Sovellus muodostaa|
|---------------|------|----------------|
|Muokattu viimeksi*|Muokkaajan käyttäjätunnus ja tiedon muokkaushetki.|X|
|Linkkien lukumäärä|Niiden tielinkkien lukumäärä, joihin valinta  kohdistuu.|X|
|Geometrian lähde|MML, Täydentävä tai Suravage.|X|
|Tienumero|Tieosoiteverkon mukainen tienumero. Lähtöaineistona Tierekisterin tieosoitteet 1.1.2019.||
|Tieosanumero|Tieosoiteverkon mukainen tieosanumero. Lähtöaineistona Tierekisterin tieosoitteet 1.1.2019.||
|Ajorata|Tieosoiteverkon mukainen ajoratakoodi. Lähtöaineistona Tierekisterin tieosoitteet 1.1.2019.||
|Alkuetäisyys**|Tieosoiteverkon etäisyyslukemien avulla laskettu alkuetäisyys. Etäisyyslukeman kohdalla alkuetäisyyden lähtöaineistona on Tierekisterin tieosoitteet 1.1.2019.|X|
|Loppuetäisyys**|Tieosoiteverkon etäisyyslukemien avulla laskettu loppuetäisyys. Etäisyyslukeman kohdalla loppuetäisyyden lähtöaineistona on Tierekisterin tieosoitteet 1.1.2019.|X|
|ELY|Väylän ELY-numero.|X|
|Tietyyppi|Muodostetaan Maanmittauslaitoksen hallinnollinen luokka -tiedoista, kts. taulukko alempana. Jos valitulla tieosalla on useita tietyyppejä, ne kerrotaan ominaisuustietotaulussa pilkulla erotettuna.|X|
|Jatkuvuus|Tieosoiteverkon mukainen jatkuvuustieto. Lähtöaineistona Tierekisterin tieosoitteet 1.1.2019.|X|

*) Muokattu viimeksi -tiedoissa vvh_modified tarkoittaa, että muutos on tullut Maanmittauslaitokselta joko geometriaan tai geometrian ominaisuustietoihin. Muokattu viimeksi -päivät ovat kaikki vähintään 29.10.2015, koska tuolloin on tehty Maanmittauslaitoksen geometrioista alkulataus VVH:n tietokantaan.

**) Tieosoiteverkon etäisyyslukemat (tieosan alku- ja loppupisteet sekä ajoratakoodin vaihtuminen) määrittelevät mitatut alku- ja loppuetäisyydet. Etäisyyslukemien välillä alku- ja loppuetäisyydet lasketaan tieosoitesegmenttikohtaisesti Viite-sovelluksessa.

__Tietyypin muodostaminen Viite-sovelluksessa__

Järjestelmä muodostaa Tietyyppi-tiedon automaattisesti Maanmittauslaitoksen aineiston pohjalta seuraavalla tavalla:

|Tietyyppi|Muodostamistapa|
|---------|---------------|
|1 Maantie|MML:n hallinnollinen luokka arvolla 1 = Valtio|
|2 Lauttaväylä maantiellä|MML:n hallinnollinen luokka arvolla 1 = Valtio ja MML:n kohdeluokka arvolla lautta/lossi|
|3 Kunnan katuosuus|MML:n hallinnollinen luokka arvolla 2 = Kunta|
|5 Yksityistie|MML:n hallinnollinen luokka arvolla 3 = Yksityinen|
|9 Ei tiedossa|MML:lla ei tiedossa hallinnollista luokkaa|

Palautteet hallinnollisen luokan virheistä voi toimittaa Maanmittauslaitokselle osoitteeseen maasto@maanmittauslaitos.fi. Mukaan selvitys virheestä ja sen sijainnista (kuvakaappaus tms.).

5.3 Kohdistaminen tieosoitteeseen tielinkin ID:n avulla
--------------------------

Kun kohdetta klikkaa kartalla, tulee selaimen osoiteriville näkyviin valitun kohteen tielinkin ID. Osoiterivillä olevan URL:n avulla voi myös kohdistaa käyttöliittymässä ko. tielinkkiin. URL:n voi lähettää sähköpostilla toiselle henkilölle, jolloin tämä pääsee käyttöliittymässä samaan paikkaan.

Esimerkiksi: https://extranet.vayla.fi/viite/#linkProperty/1204420 näkyy kuvassa osoiterivillä (5). 1204420 on tielinkin ID.

![Kohdistaminen tielinkin ID:llä](k8.jpg)

_kuva 5.7: Kohdistaminen tielinkin ID:llä._

# 6. Tieosoiteprojekti
--------------------------

6.1 Uusi tieosoiteprojekti
--------------------------

Uuden tieosoiteprojektin tekeminen aloitetaan klikkaamalla painiketta Tieosoiteprojektit (1) ja avautuvasta ikkunasta painiketta Uusi tieosoiteprojekti (2).

![Uusi tieosoiteprojekti](k17.jpg)

_Kuva 6.1: Tieosoiteprojektit-painike ja Uusi tieosoiteprojekti -painike._

Näytön oikeaan reunaan avautuu lomake tieosoiteprojektin perustietojen täydentämistä varten. Jos käyttäjä on ollut katselutilassa,  sovellus siirtyy tässä vaiheessa automaattisesti muokkaustilaan.

Pakollisia tietoja ovat nimi ja projektin muutosten voimaantulopäivämäärä, jotka on merkattu lomakkeelle oranssilla (3). Projektiin ei tarvitse varata yhtään tieosaa. Lisätiedot-kenttään käyttäjä voi halutessaan tehdä muistiinpanoja tieosoiteprojektista. Tiedot tallentuvat painamalla Jatka toimenpiteisiin -painiketta (4). Poistu-painike (5) sulkee projektin tietoja tallentamatta.

![Uusi tieosoiteprojekti](k19.jpg)

_kuva 6.2: Tieosoiteprojektin perustietojen täyttäminen._

Projektin tieosat lisätään täydentämällä niiden tiedot kenttiin TIE, AOSA sekä LOSA ja painamalla painiketta Varaa (6). __Kaikki kentät tulee täyttää, jos haluaa varata tieosan!__ 

Varauksen yhteydessä järjestelmä tekee varattaville tieosille tarkastukset:

- Onko varattava tieosa olemassa projektin voimaantulopäivänä
- Onko varattava tieosa vapaana vai onko se jo varattu toiseen projektiin

Virheellisistä varausyrityksistä järjestelmä antaa virheilmoituksen.  __Käyttäjän tulee huomioida, että varauksen yhteydessä kaikki kentät (TIE, AOSA, LOSA) tulee täyttää, tai käyttäjä saa virheilmoituksen!__

Varaa-painikkeen klikkauksen jälkeen tieosan tiedot tulevat näkyviin lomakkeelle (7).

![Uusi tieosoiteprojekti](k22.jpg)

_kuva 6.3: Tieosan tiedot lomakkeella Varaa-painikkeen painamisen jälkeen._

Tieosoiteprojekti tallentuu automaattisesti painikkeesta Jatka toimenpiteisiin, jolloin tiedot tallentuvat tietokantaan ja sovellus siirtyy toimenpidenäytölle. Varaamisen yhteydessä Viite kohdistaa kartan varatun tieosan alkuun. Poistu-painikkeesta projekti suljetaan ja käyttäjältä varmistetaan, halutaanko tallentamattomat muutokset tallentaa. Projektiin pääsee palaamaan Tieosoiteprojektit-listan kautta. 

Käyttäjä voi poistaa varattuja tieosia klikkaamalla Roskakori-kuvaketta (8) valitsemansa tieosan kohdalla Projektiin varatut tieosat -listalla. Mikäli tieosille ei ole tehty muutoksia, vaan ne on vain varattu projektiin, varaus poistetaan projektista. Jos tieosille on tehty muutoksia, Viite pyytää käyttäjää vahvistamaan poiston.

Käyttäjä voi poistaa projektissa muodostettuja tieosia klikkaamalla Roskakori-kuvaketta valitsemiensa tieosien kohdalla Projektissa muodostetut tieosat -listalla. Käyttäjää pyydetään vahvistamaan poisto.  

Keskeneräisen projektin voi poistaa Poista projekti –painikkeella (9), jolloin projekti ja sen varaamat aihiot ja tehdyt muutokset poistetaan. Viite pyytää käyttäjää vahvistamaan poiston. 

![Uusi tieosoiteprojekti](k20.jpg)

_Kuva 6.4: Kun tieosa on varattu projektiin, Viite kohdistaa kartan siten, että tieosa näkyy kartalla kokonaisuudessaan._  

6.2 Olemassa oleva tieosoiteprojekti
--------------------------

Tieosoiteprojektit-listalla näkyvät kaikkien käyttäjien projektit. Projektit on järjestetty ELY-koodien mukaiseen järjestykseen pienimmästä suurimpaan ja niiden sisällä projektin nimen ja käyttäjätunnuksen mukaiseen järjestykseen. 

Järjestystä voi muuttaa sarakkeiden nuolipainikkeilla. Käyttäjä-sarakkeen suodatinpainikkeella saa valittua listalle omat projektinsa. Toisen käyttäjän projektit suodatetaan kirjoittamalla ko. käyttäjän tunnus avautuvaan syöttökenttään.

Viety tierekisteriin -tilaiset projektit näytetään listalla vuorokauden ajan niiden viennistä tierekisteriin. Kaikki tierekisteriin viedyt projektit saa näkyviin klikkaamalla listan alareunassa olevaa valintaruutua, ja vastaavasti ne saa pois näkyvistä poistamalla valinnan.

Listan oikeassa alakulmassa olevaa Päivitä lista -painiketta klikkaamalla projektien muuttuneet tilat päivittyvät listalla.

Tallennetun tieosoiteprojektin saa auki Tieosoiteprojektit-listalta painamalla Avaa-painiketta. Avaamisen yhteydessä sovellus kohdistaa kartan paikkaan, jossa käyttäjä on viimeksi tallentanut toimenpiteen. Mikäli toimenpiteitä ei ole tehty, karttanäkymä rajautuu siten, että kaikki varatut aihiot näkyvät karttanäkymässä.

Projektia ei voi muokata, jos sen tila on joko Lähetetty tierekisteriin, Tierekisterissä käsittelyssä tai Viety tierekisteriin.

Tieosoiteprojektit-lista suljetaan yläpalkin oikeassa kulmassa olevasta rastipainikkeesta.

![Uusi tieosoiteprojekti](k26.jpg)

_Kuva 6.5: Tieosoiteprojektit-lista._

# 7. Muutosilmoitusten tekeminen tieosoiteprojektissa
--------------------------

Tieosoiteprojektissa on mahdollista tehdä seuraavia muutosilmoituksia:

- lakkautus (tieosoitteen lakkautus) 
- uusi (lisätään uusi tieosoite osoitteettomalle linkille) 
- ennallaan (osa tieosoitteesta säilyy ennallaan, kun osaa siitä muutetaan)
- siirto (tieosan alkuetäisyys- ja loppuetäisyysarvot päivitetään, kun muulle osalle tieosaa tehdään muutos)
- numeroinnin muutos (kokonaisen tieosan tienumeron ja/tai tieosanumeron voi muuttaa manuaalisesti) 
- kääntö (tieosoitteen kasvusuunnan kääntö)
- etäisyyslukeman muutos (etäisyyslukeman loppuarvon voi syöttää tieosalle manuaalisesti)
- ELY-koodin, jatkuvuuden ja tietyypin muutos

Tieosoiteprojektissa muutostoimenpiteitä pääsee tekemään klikkaamalla Jatka toimenpiteisiin -painiketta tieosoitemuutosprojektin perustietojen lomakkeella. Tämän jälkeen sovellus muuttaa varatut tieosat muokattaviksi kohteiksi ja ne näkyvät avautuvassa karttanäkymässä keltaisella korostettuina (1). Mikäli toimenpiteenä lisätään uusi tieosoite eikä tieosia ole varattu projektiin, kartalta ei valikoidu mitään ennen käyttäjän tekemää valintaa. 

Projektitilassa voi valita kartalta klikkaamalla projektiin tieosia, tuntemattomia tielinkkejä, muun tieverkon linkkejä tai suunnitelmalinkkejä (Suravage-linkkejä). Suunnitelmalinkit saa pois piirrosta ja piirtoon sivun alapalkissa olevasta valintaruudusta. Ne ovat oletuksena piirrossa. Tieverkon tieosoitetietoja voi katsella kartalla viemällä hiiren tieosoitelinkin päälle. Tällöin tielinkin infolaatikko tulee näkyviin.

Projektin nimen vieressä on sininen kynäkuvake (2), josta pääsee projektin perustietojen lomakkeelle muokkaamaan projektin tietoja. Lisäksi oikeassa yläkulmassa on Sulje-painike (3), josta pääsee Viitteen alkutilaan.

![Aihio](k36.jpg)

_Kuva 7.1: Projektissa muokattavissa olevat varatut tieosat näkyvät kartalla keltaisella värillä ja suuntanuolet ovat tien alkuperäisen tieluokan värin mukaiset._

Projektin muutosilmoitukset tallentuvat projektin yhteenvetotaulukkoon, jonka voi avata toimenpidenäkymässä Avaa projektin yhteenvetotaulukko -painikkeesta (4). Yhteenvetotaulukon toiminta on kuvattu tarkemmin luvussa 7.2. Lisäksi kaikkien projektien muutostiedot voidaan lähettää tierekisteriin klikkaamalla vihreää Lähetä muutosilmoitus Tierekisteriin -painiketta (5). Muutosilmoituksen lähettäminen on kuvattu luvussa 7.4. 

Kun keltaista, muokattavaa kohdetta klikataan kerran kartalla, muuttuu valittu osuus vihreäksi ja oikeaan reunaan tulee pudotusvalikko, josta voi valita kohteelle tehtävän muutosilmoituksen (esim. lakkautus). Kertaklikkauksella valitaan kartalta homogeeninen jakso (= sama tienumero, tieosanumero, ajoratakoodi, tietyyppi ja jatkuvuus). Kaksoisklikkaus tai Ctrl+klikkaus valitsee yhden tieosoitesegmentin verran (tielinkin mittainen osuus). Kun halutaan valita vain osa tieosan linkeistä, kaksois- tai Ctrl+klikataan ensimmäistä linkkiä ja seuraavat linkit lisätään valintaan Ctrl+klikkauksella samalta tieosalta. Samalla tavalla voi myös poistaa yksittäisiä linkkejä valinnasta. 

![Valittu kohde](k37.jpg)

_Kuva 7.2: Kun keltaista, muokattavissa olevaa kohdetta klikataan, muuttuu tieosa vihreäksi ja oikeaan laitaan tulee näkyviin valikko, jossa on valittavissa tieosoitemuutosprojektin mahdolliset muutosilmoitukset._

Muutokset tallennetaan oikean alakulman Tallenna-painikkeesta. Ennen tallennusta muutokset voi perua Peruuta-painikkeesta, jolloin Viite palaa edeltävään vaiheeseen.

Jos käyttäjä on jo tehnyt projektissa muutoksia tieosoitteille, ne tulevat näkyviin lomakkeelle klikatessa kyseistä tielinkkiä. Jos esimerkiksi tieosalle on toteutettu Lakkautus, tieosaa valittaessa tiedot sen lakkautuksesta ilmestyvät lomakkeelle ja tieosalle on mahdollista tehdä toinen toimenpide. Mahdolliset uudet toimenpidevaihtoehdot kunkin toimenpiteen tallentamisen jälkeen on kuvattu tarkemmin seuraavissa luvuissa. 

Selite projektitilassa on erilainen kuin katselutilassa. Projektitilan selite kuvaa linkkiverkkoon tehdyt toimenpiteet, kun taas katselutilan selite kuvaa tieluokitusta.

7.1 Muutosilmoitusten kuvaukset
--------------------------

7.1.1 Lakkautus
--------------------------

Kun halutaan lakkauttaa joko tieosia, tieosa tai osa tieosasta, ko. osat pitää ensin varata projektiin. Varaaminen tehdään luvussa 6.1 esitetyllä tavalla syöttämällä projektitietojen lomakkeelle haluttu tienumero ja tieosa sekä painamalla Varaa-painiketta.

Tämän jälkeen klikataan Jatka toimenpiteisiin -painiketta, jolla siirrytään toimenpidelomakkeelle tekemään tieosoitemuutosta. Toimenpidelomakkeella valitaan kartalta projektiin varattu tieosa, -osat tai tarvittavat linkit valitusta tieosasta. Ne muuttuvat valittuina vihreiksi. (Shift+kaksoisklikkaus-painalluksella voi lisätä yksittäisiä linkkejä valintaan tai poistaa yksittäisiä linkkejä valinnasta.) Toimenpide-lomakkeelle tulee tiedot valituista linkeistä sekä pudotusvalikko, josta valitaan Lakkautus. Tämän jälkeen tallennetaan muutos projektiin. Lakkautettu linkki tulee näkyviin mustalla ja sen tiedot päivittyvät yhteenvetotaulukkoon, jonka voi avata sinisestä Avaa projektin yhteenvetotaulukko -painikkeesta. Yhteenvetotaulukon toiminta on kuvattu luvussa 7.2. Mikäli on lakkautettu vain osa tieosan linkeistä, tulee tieosan muut kuin lakkautetut linkit käsitellä joko Ennallaan- tai Siirto-toimenpiteillä tilanteesta riippuen. Kun tarvittavat muutokset projektissa on tehty, muutostiedot voi lähettää Tierekisteriin painamalla Lähetä muutosilmoitus Tierekisteriin -painiketta. 

7.1.2 Uusi
--------------------------
Toimenpiteellä määritetään uusi tieosoite tieosoitteettomille linkeille. Tieosoitteettomia muun tieverkon linkkejä, jotka piirtyvät kartalle harmaina tai tuntemattomia mustia linkkejä, joissa on kysymysmerkkisymboli tai Suravage-linkkejä, voi valita kerta- tai kaksoisklikkauksella, kuten muitakin tielinkkejä. Kaksoisklikkaus valitsee yhden tielinkin ja Ctrl+klikkauksella voi lisätä tai poistaa valintaan linkkejä yksi kerrallaan. Kertaklikkaus valitsee homogeenisen jakson, jossa käytetään VVH:n tielinkin tienumeroa ja tieosanumeroa. Tienumeron tai tieosanumeron puuttuessa valinnassa käytetään tienimeä.

Valitut tielinkit näkyvät kartalla vihreällä korostettuna. Kun valitaan Toimenpiteet-pudotusvalikosta Uusi (1), lomakkeelle avautuvat kentät uuden tieosoitteen tiedoille (2). Jos valitulla tieosuudella on jo olemassa VVH:ssa tienumero ja tieosanumero, ne esitäyttyvät kenttiin automaattisesti.

Tietyyppiä voi muokata pudotusvalikosta (3). Tien nimi (4) on pakollinen tieto, kun tien numero on pienempi kuin 70000. Muutokset tallennetaan Tallenna-painikkeella (5). Ennen tallennusta muutokset voi perua Peruuta-painikkeesta. 

![Uusi tieosoite](k43.jpg)

_Kuva 7.3: Kun valitaan toimenpide Uusi, oikeaan laitaan tulee näkyviin kentät uuden tieosoitteen syöttämistä varten._ 

Jos uusi tieosoite on jo olemassa projektin alkupäivänä tai se on varattuna toisessa tieosoiteprojektissa, käyttäjä saa tästä ilmoituksen.

![Tieosoite on jo olemassa](k44.JPG)

_kuva 7.4: Tieosoite on jo olemassa projektin alkupäivänä._

Kun tieosoitteen tiedot on tallennettu, lomakkeelle tulee pudotusvalikko, josta valitaan jatkuvuuskoodi (1). Uudelle tieosoitteelle määrittyy aluksi satunnainen kasvusuunta, joka näytetään kartalla pinkeillä nuolilla. Kasvusuunnan voi vaihtaa Käännä tieosan kasvusuunta -painikkeella (2).

![Kasvusuunnan vaihto](k47.jpg)

_kuva 7.5: Valittuna olevan uuden tieosoitteen kasvusuunta vaihtuu lomakkeen Käännä tieosan kasvusuunta -painikkeesta._

Uuden tieosoitteen linkit piirtyvät kartalle pinkillä (1). Tieosan alku- ja loppupisteisiin (2) sijoitetaan automaattisesti etäisyyslukema-symbolit. Viite laskee uudelle tieosuudelle automaattisesti myös linkkien m-arvot käyttäen VVH:n tietoja. 

![Uusi tieosoite pinkilla](k46.jpg)

_kuva 7.6: Uuden tieosoitteen linkit piirtyvät kartalle pinkillä. Tieosan voi valita klikkaamalla, jolloin se korostuu vihreällä._

Tallennettuun tieosoitteeseen voi jatkaa uusien linkkien lisäämistä vaiheittain. Ensin valitaan tallennetun tieosan jatkeeksi seuraava linkki. Sitten valitaan lomakkeelta toimenpide Uusi, annetaan linkille sama tieosoite (TIE= tienumero, OSA=tieosanumero, AJR=ajoratakoodi) ja tallennetaan. Viite täyttää automaattisesti ELY-koodin, joka määräytyy tielinkin kuntakoodin perustella VVH:sta. 

Projektin voi myös tallentaa ja sulkea ja jatkaa lisäystä samaan tieosoitteeseen myöhemmin. Kasvusuunta lisätylle osuudelle määräytyy aiemmin osoitteistettujen linkkien mukaan ja sitä voi edelleen muuttaa Käännä kasvusuunta -painikkeella. M-arvot päivittyvät koko tieosalle, jolle on annettu sama tieosoite.

Tieosoitteen voi antaa Viitteessä myös ns. Suravage-linkeille (SuRavaGe = Suunniteltu rakentamisvaiheen geometria). Suravage-tiet näkyvät Viitteessä vaaleanpunaisella värillä ja niissä näkyy myös tieosoitteen kasvusuuntanuolet. 

__Uuden kiertoliittymän alkupaikan muuttaminen__

Jos Uusi-toimenpiteellä tieosoitteistetulla kiertoliittymän linkeillä on VVH:ssa (esim. Suravage-linkit) tienumero, kiertoliittymän voi ns. "pikaosoitteistaa". Pikaosoitteistaminen tapahtuu kertaamalla kiertoliittymän alkukohdaksi haluttua linkkiä. Tällöin koko kiertoliittymän linkit tulevat valituiksi. Uusi toimenpide asettaa alkukohdaksi klikatun linkin.

Muussa tapauksessa kiertoliittymän alkukohta asetetaan manuaalisesti kahdessa vaiheessa:
1. Valitaan alkupaikka kaksoisklikkaamalla kiertoliittymän linkkiä tieosoitteen haluttuun alkupaikkaan. Valitulle linkille annetaan Uusi-toimenpiteellä tieosoite. 
2. Kiertoliittymän loput linkit valitaan Ctrl+klikkaamalla ja annetaan niille sama tieosoite.    

Tieosoiteprojektissa Uusi-toimenpiteellä jo tieosoitteistetun kiertoliittymän alkupaikka muutetaan palauttamalla kiertoliittymä ensin tieosoitteettomaksi ja osoitteistamalla se uudelleen. Valitse tieosoitteistettu kiertoliittymä ja käytä toimenpidettä Palautus aihioksi tai tieosoitteettomaksi. Toimenpiteen jälkeen kiertoliittymän voi tieosoitteistaa uudelleen halutusta alkupaikasta aloittaen.

7.1.3 Ennallaan
--------------------------
Tieosan linkkien tieosoitteen voi säilyttää ennallaan esimerkiksi silloin, kun osalle tieosaa halutaan tehdä tieosoitemuutoksia ja osan säilyvän ennallaan. Tällöin tieosa käsitellään toimenpiteellä Ennallaan. Toimenpide tehdään varaamalla ensin projektitietojen formilla projektiin muokattava tieosa tai -osat. Seuraavaksi siirrytään toimenpidenäytölle Jatka toimenpiteisiin -painikkeella. Valittu tieosa tai sen tietyt linkit valitaan kartalta, jolloin ne muuttuvat vihreiksi, ja lomakkeelle ilmestyy pudotusvalikko. Valikosta valitaan toimenpide Ennallaan ja tallennetaan muutokset.   

7.1.4 Siirto
--------------------------
Siirto-toimenpide tehdään tieosalle uusien m-arvojen laskemiseksi. Siirtoa käytetään, kun osa tieosan linkeistä käsitellään jollain muulla toimenpiteellä ja loppujen linkkien m-arvot täytyy laskea uudelleen. Esimerkkinä osalle tieosan linkeistä voidaan tehdä lakkautus, lisätä uusia linkkejä ja pitää osa linkeistä ennallaan. Siirto tehdään tieosoiteprojektiin varatulle tieosalle siten, että tieosalle on ensin tehty muita toimenpiteitä, kuten lakkautus, uusi tai numerointi. Linkit, joille siirto tehdään, valitaan kaksoisklikkaamalla ensimmäinen haluttu linkki ja lisäämällä valintaan Ctrl+klikkaamalla linkkejä. Sitten valitaan toimenpidevalikosta siirto ja tallennetaan. Siirretyt linkit muuttuvat toimenpiteen tallennuksen jälkeen punaiseksi. Muutokset näkyvät projektin yhteenvetotaulukossa.   

7.1.5 Numerointi
--------------------------
Tieosoitteen numeroinnin muutoksella tarkoitetaan Viitteessä tienumeron ja/tai tieosanumeron muuttamista. 
Projektiin varataan tarvittava(t) tieosa(t). Varaamisen jälkeen siirrytään toimenpidelomakkeelle Jatka toimenpiteisiin -painikkeella. Valitaan muokattava, keltaisella näkyvä varattu tieosa klikkaamalla kartalta. Tieosa muuttuu vihreäksi. Viite poimii tällöin koko tieosan mukaan valintaan, vaikkei se näkyisi kokonaisuudessaan karttanäkymässä, ja käyttäjälle tulee tästä ilmoitus. Mikäli on tarpeen muuttaa vain tietyn linkin numerointia tieosalla, tehdään valinta kaksoisklikkauksella halutun linkin päältä. Jos valitaan lisää yksittäisiä linkkejä, tehdään se Ctrl+klikkaamalla. Toimenpide-lomakkeelle syötetään uusi numerointi (tienumero ja/tai tieosanumero) ja tallennetaan muutokset. Numeroitu osuus muuttuu tallennettaessa ruskeaksi.

Koska numeroinnin muutos kohdistuu koko tieosaan, muita toimenpiteitä ei tallennuksen jälkeen tarvitse tehdä. 

7.1.6 Kääntö
--------------------------
Tieosoitteen kasvusuunnan voi kääntää Viitteessä joko esimerkiksi siirron tai numeroinnin yhteydessä. Kääntö tapahtuu joko automaattisesti tai tietyissä tilanteissa käyttäjä tekee sen manuaalisesti.  

#### Automaattinen kääntö siirron yhteydessä:

Kun siirretään tieosa (osittain tai kokonaan) toiselle tieosalle, jolla on eri tieosoitteen kasvusuunta, Viite päättelee siirron yhteydessä kasvusuunnan siirrettäville linkeille. 

Alla olevassa kuvasarjassa on tehty siirto ja kääntö osalle tieosaa. Projektiin on varattu tie 459, osa 1 ja tie 14, osa 1 (näkyvät kartalla keltaisella). Osa tien 14 linkeistä halutaan siirtää tielle 459 osalle 1 (lännen suuntaan), jolloin siirrettävät linkit valitaan kartalta. Lomakkeelta valitaan muutosilmoitus Siirto (1). Annetaan kohtaan TIE arvoksi kohdetien numero 459. Muut tiedot säilyvät tässä tapauksessa samana, mutta myös tieosaa, tietyyppiä ja jatkuvuuskoodia tulee tarvittaessa muuttaa. Tallennetaan muutokset (2). Kohde muuttuu siirretyksi ja tieosoitteen kasvusuunta päivittyy vastaamaan tien 459 kasvusuuntaa (3). 

Tämän jälkeen siirtoon ja kääntöön voi valita lisää linkkejä tieltä 14 tai jättää loput ennalleen. Jälkimmäisessä tilanteessa loput projektiin valitut keltaiset aihiot tulee aina käsitellä, jotta muutosilmoitukset voi lähettää tierekisteriin. Mikäli muuta ei tehdä, tulee tie 459, osa 1 valita kartalta ja tehdä sille muutosilmoitus Siirto ja painaa Tallenna. Samoin tehdään tielle 14 osalle 1. Ilmoitusten yhteenvetotaulukko avataan ja mikäli tiedot ovat valmiit, voi ne lähettää tierekisteriin vihreästä painikkeesta.  

![Siirto ja kääntö](k48.jpg)

![Siirto ja kääntö](k51.jpg)

![Siirto ja kääntö](k52.jpg)

_Kuva 7.7: Kuvasarjassa siirretään osa tiestä 14 tielle 459. Tieosoitteiden kasvusuunnat teillä ovat vastakkaiset, jolloin siirrossa tien 14 kasvusuunta kääntyy._

_Kuva 7.8: Manuaalinen kääntö siirron ja numeroinnin yhteydessä:_

Manuaalista kääntöä varten Viitteessä on Käännä kasvusuunta -painike. Painike aktivoituu lomakkeelle, kun käyttäjä on tehnyt varaamalleen aihiolle toimenpiteen ja tallentanut sen. Kun käsiteltyä aihiota (on tehty muutosilmoitus siirto tai numerointi) klikataan kartalla, lomakkeella näkyvät tehty ilmoitus ja sen tiedot sekä Käännä kasvusuunta -painike. Kun sitä klikataan sekä tallennetaan, kasvusuunta kääntyy ja yhteenvetotauluun tulee tieto käännöstä oman sarakkeeseen Kääntö (rasti ruudussa). 
	
_Kuva 7.9: Kaksiajorataisen osuuden kääntö_

Kun käännetään tieosan kaksiajoratainen osuus, se tehdään edellä kuvatulla tavalla siirron tai numeroinnin yhteydessä yksi ajorata kerrallaan. Kartalta valitaan haluttu ajorata ja lomakkeelta joko siirto tai numerointi. Määritetään uusi ajoratakoodi sekä muut tarvittavat tieosoitemuutostiedot lomakkeelle ja tallennetaan. Mikäli tieosoitteen kasvusuunta ei automaattisesti käänny (esim. kun käsitellään yhtä tieosaa), tehdään kääntö manuaalisesti Käännä kasvusuunta -painikkeella. Yhteenvetotaulussa Kääntö-sarake sekä muutosilmoituksen rivit päivittyvät. 
 
7.1.7 Etäisyyslukeman muutos
--------------------------
Tieosoiteprojektissa uudelle tieosoitteistettavalle tieosalle on mahdollista asettaa käyttäjän antama tieosan loppuetäisyyslukema. Ensin valitaan haluttu tieosa kartalta, jonka jälkeen lomakkeelle ilmestyy kenttä, johon loppuetäisyyden voi muuttaa. Muutettu arvo huomioidaan lomakkeella punaisella huutomerkillä. 

7.1.8 ELY-koodin, jatkuvuuden ja tietyypin muutos
--------------------------
Viitteessä voi muokata ELY-koodia, jatkuvuutta ja tietyyppiä. Näitä muutoksia voi tehdä esimerkiksi Ennallaan-toimenpiteellä, jolloin lomakkeelle tulee pudotusvalikot ELYlle, jatkuvuudelle ja tietyypille. Uudet arvot annetaan valitulle aihiolle ja tallennetaan. Jatkuvuus-koodi näytetään valinnan viimeiseltä linkiltä ja muutokset kohdistuvat myös viimeiseen linkkiin. Tietyypin ja ELY-koodin muutos kohdistuu kaikkiin valittuihin linkkeihin. Ennallaan-toimenpiteen lisäksi näitä arvoja voi muokata aina, kun ne ovat muutosilmoituksen yhteydessä lomakkeella muokattavissa. 

Huom! Jatkuvuuskoodia 5 on valittavissa kaksi eri vaihtoehtoa. Koodia _5 Jatkuva (Rinnakkainen linkki)_ käytetään kaksiajorataisella tiellä osoittamaan rinnakkainen linkki silloin, kun toisella ajoradalla on lievä epäjatkuvuus, eikä automaatio ole löytänyt oikeaa linkkiparia. Jatkuvuuskoodi _4 Lievä epäjatkuvuus_ annetaan normaalisti toisen ajoradan epäjatkuvuuskohtaan. Viite antaa automaattisesti kalibrointipisteet molemmille ajoradoille. Jos rinnakkaisen ajoradan kalibrointipiste ei ole halutussa kohdassa, valitaan haluttu rinnakkainen linkki ja annetaan sille koodiksi _5 Jatkuva (Rinnakkainen linkki)_. Tällöin kalibrointipiste tulee kyseisen linkin loppuun, ja ajoratojen AET- ja LET-arvot saadaan täsmättyä lievään epäjatkuvuuskohtaan.

7.1.9 Useiden muutosten tekeminen samalle tieosalle
--------------------------

7.2 Muutosilmoitusten tarkastelu taulukkonäkymässä
--------------------------

Projektin muutosilmoitusnäkymässä on mahdollista tarkastella ilmoitusten yhteenvetotaulukkoa. Avaa projektin yhteenvetotaulukko -painikkeella (1) avautuu taulukkonäkymä, joka kertoo projektissa olevien tieosoitteiden vanhan ja uuden tilanteen sekä tehdyn muutosilmoituksen. Taulukossa rivit on järjestetty suurimmasta pienimpään tieosoitteen mukaan (tie, tieosa). Rivien järjestystä voi muokata nuolipainikkeilla (2). Yhteenvetotaulukon AET- ja LET-arvot päivittyvät oikein vasta kun kaikki tieosan aihiot on käsitelty.

Taulukon rivien tietoja voi kopioida maalaamalla halutut tiedot hiirellä ja kopioimalla ne. 

Taulukon kokoa saa muutettua taulukon yläkulmassa olevasta painikkeesta (3). Taulukon voi pitää auki muokatessa ja muutokset päivittyvät taulukkoon tallennettaessa. Viite-sovelluksen voi venyttää kahdelle näytölle, joista toisella voi tarkastella muutostaulukkoa ja toisella käyttää karttanäkymää. Taulukkoa voi liikuttaa tarraamalla osoittimella yläpalkista. Yhteenvetotaulukko ei välttämättä näy oikeanlaisena, jos selaimen zoom-taso on liian suuri. Ongelma korjaantuu palauttamalla selaimen zoom-taso normaaliksi (Firefox/Chrome Ctrl+0). 

Taulukon saa pois näkyvistä Sulje-painikkeesta (4).

![Avaus](k41.jpg)

_Kuva 7.10: Muutosilmoitustaulukkonäkymä._

7.3 Tarkastukset
--------------------------

Viite-sovellus tekee tieosoiteprojektissa automaattisia tarkastuksia, jotka auttavat käyttäjää valmistelemaan muutosilmoituksen Tierekisterin vaatimaan muotoon. Tarkastukset ovat projektissa jatkuvasti päällä ja reagoivat projektin tilan muutoksiin.  Avatun projektin tarkastusilmoitukset tulevat esiin Jatka toimenpiteisiin -painikkeen klikkauksen jälkeen. Ylimpinä näytetään infotiedot oranssilla korostettuina ja niiden alapuolella muut virheilmoitukset.

![Tarkastusilmoitusnäkymä](k49.jpg)

_Kuva 7.11: Tarkastusilmoitukset näkyvät projektissa oikealla._

Tarkastusilmoitus koostuu seuraavista kentistä:

|Kenttä|Kuvaus|
|------|------|
|Linkids|Tarkastuksen kohteena oleva yksittäisen tielinkin ID. Vaihtoehtoisesti linkkien lukumäärä jos tarkastusilmoitus koskee useampaa linkkiä.|
|Virhe|Kuvaus tarkastuksen ongelmatilanteesta.|
|Info|Mahdollisia lisäohjeita tarkastuksen virhetilanteen korjaamiseksi.|

![Tarkastusilmoitus](k50.JPG)

_Kuva 7.12: Tielinkki 7304402 on tieosan viimeinen linkki, mutta siltä puuttuu jatkuvuuskoodi Tien loppu._ 

Karttanäkymä kohdistuu tarkastusilmoituksen kohteena olevan tielinkin keskikohtaan painamalla Korjaa-painiketta. Painiketta uudestaan klikkaamalla kohdistus siirtyy seuraavaan tielinkkiin, jos sama tarkastus kohdistuu useampaan linkkiin. Käyttäjä voi valita tarkastusilmoituksen kohteena olevan tielinkin ja tehdä sille korjaavan toimenpiteen. 

#### Tieosoiteprojektissa tehtävät tarkastukset:

Tieosoiteprojektiin kohdistuvat tarkastukset:

- Uusi tieosa ei saa olla varattuna jossakin toisessa tieosoiteprojektissa
- Tieosoitteen kasvusuunta ei saa muuttua kesken tien
- Tieosoitteellinen tie ei saa haarautua muuten kuin ajoratakoodivaihdoksessa
- Ajoratojen 1 ja 2 tulee kattaa samaa osoitealue
- Tieosoitteelta ei saa puuttua tieosoiteväliä (katkoa m-arvoissa)

Jatkuvuuden tarkastukset:

- Tieosan sisällä jatkuvissa kohdissa (aukkopaikka alle 0,1 m), jatkuvuuskoodin tulee olla _5 Jatkuva_.

- Tieosan sisällä jatkuvissa kohdissa rinnakkaisen linkin jatkuvuuskoodin tulee olla _5 Jatkuva (Rinnakkainen linkki)_.

- Tieosan sisällä epäjatkuvuuskohdissa (aukkopaikka yli 0,1 m) jatkuvuuskoodi tulee olla _4 Lievä epäjatkuvuus_. 
Tieosan sisäisen epäjatkuvuuden pituudelle ei ole asetettu ylärajaa.

- Tieosan lopussa tulee olla jatkuvuuskoodi _2 Epäjatkuva_ tai _4 Lievä epäjatkuvuus_, jos ennen tien seuraavaa tieosaa on epäjatkuvuuskohta. Seuraavaa tieosaa ei ole välttämättä valittu projektiin, joten tarkastus huomioi myös projektin ulkopuoliset tieosat.

- Tieosoitteen viimeisellä (suurin tieosanumero) tieosalla tulee olla jatkuvuuskoodi _1 Tien loppu_.

- Jos tieosoitteen viimeinen tieosa lakkautetaan kokonaan, tien edellisellä tieosalla tulee olla jatkuvuuskoodi _1 Tien loppu_. Tätä tieosaa ei ole välttämättä valittu projektiin ja siksi tarkastus ulottuu myös projektin ulkopuolisiin tieosiin.

- Jos tieosan seuraava tieosa on eri ELY-koodilla, jatkuvuuskoodin tulee olla tieosan lopussa _3 ELYn raja_.

7.4 Muutosilmoitusten lähettäminen
--------------------------

Muutosilmoitus viedään tierekisteriin avaamalla ensin yhteenvetotaulukko klikkaamalla oikean alakulman sinistä Avaa projektin yhteenvetotaulukko -painiketta. Kun projektilla ei ole enää korjaamattomia tarkastusilmoituksia, aktivoituu vihreä Lähetä muutosilmoitus Tierekisteriin -painike. Painikkeen painamisen jälkeen sovellus ilmoittaa muutosilmoituksen tekemisestä Muutosilmoitus lähetetty Tierekisteriin -viestillä.

![Muutosilmoituksen painike](k38.jpg)

_Kuva 7.13: Muutosilmoituksen lähetyspainike oikeassa alakulmassa._

Kun muutosilmoitus on lähetetty, muuttuu projektin tilatiedoksi Lähetetty tierekisteriin. Viite-sovellus tarkistaa minuutin välein tierekisterin tilanteen. Kun muutos on käsitelty siellä, tilatiedoksi tulee Viety tierekisteriin ja projekti on valmis. Mikäli muutosilmoitus ei ole mennyt läpi Tierekisterissä, tilaksi päivittyy Virhe tierekisterissä ja listalle tulee oranssi Avaa uudelleen -painike. Viemällä hiiren Virhe tierekisterissä -tekstin päälle virheen infolaatikko tulee näkyviin. Virhe korjataan avaamalla projekti ja tekemällä tarvittavat muokkaukset, jonka jälkeen ilmoitus lähetetään uudelleen Tierekisteriin.  

|Tieosoiteprojektin tila|Selitys|
|-|-|
|Keskeneräinen|Projekti on työn alla ja sitä ei ole vielä lähetetty tierekisteriin.|
|Lähetetty tierekisteriin|Projekti on lähetetty tierekisteriin.|
|Tierekisterissä käsittelyssä|Projekti on tierekisterissä käsittelyssä. Tierekisteri käsittelee projektin sisältämiä muutosilmoituksia.|
|Viety tierekisteriin|Projekti on hyväksytty tierekisterissä. Muutokset näkyvät myös Viite-sovelluksessa.|
|Virhe tierekisterissä|Tierekisteri ei hyväksynyt projektia. Tierekisterin tarkempi virheilmoitus tulee näkyviin viemällä osoittimen "Virhe tierekisterissä"-tekstin päälle. Projektin voi avata uudelleen.|
|Virhetilanne Viitteessä|Projekti on lähetty Tierekisteriin ja se on Tierekisterin hyväksymä, mutta projektin tiedot eivät piirry Viite-sovelluksessa.| 

# 8. Tienimien ylläpito
--------------------------

Viitteessä teillä, joiden tienumero on suurempi kuin 70 000, tienimi on pakollinen tieto. Tienimi annetaan uutta tietä luotaessa projektilomakkeella.

Jos annettu tienimi täytyy vaihtaa tai sitä pitää muokata esim. kirjoitusvirheen vuoksi, käytetään Tiennimen ylläpito -työkalua, joka avataan Tieosoiteprojektit-painikkeen alta näytön oikeasta reunasta (kuva 6.1).

Muokattavat tienimet haetaan tienumeron perusteella kirjoittamalla tien numero lomakkeen syöttökenttään (1) (kuva 8.1). Tiennimen ylläpito -ikkuna suljetaan klikkaamalla oikeasta yläkulmasta löytyvästä rastista (2). Lomakkeen alareunassa on huomautus (3), että tallennetut muutokset päivittyvät tierekisteriin tunnin sisällä.

![Tienimen ylläpito](k53.JPG) 

_Kuva 8.1: Tienimen ylläpito -työkalun lomakkeen etusivu._

8.1 Tien nimen muokkaaminen, kun tiellä on jo nimi
--------------------------

Kun tienumero on kirjoitettu syöttökenttään, painetaan Hae-painiketta, ja hakutulokset listautuvat hakukentän alapuolelle otsikkorivien alle (1). Listalla näkyvät tien aiemmat nimet alku- ja loppupäivämäärineen. Voimassa oleva nimi on listassa alimpana (2). Sitä voi muokata (syöttökenttä on valkoinen). Rivin lopussa on [+] –painike (3), jos haettu tie on voimassa oleva. Voimassa olevalla nimellä ei ole loppupäivämäärää (4). Hakukenttä ei tyhjene, mutta uuden haun voi tehdä kirjoittamalla uuden tienumeron hakukenttään.

![Listaus tienimistä ja uudelleenhaku](k54.JPG)

_Kuva 8.2: Listaus tienimistä ja uudelleenhaku._

Jos tie on lakkautettu, tien nimi näkyy harmaana (kuva 8.3) eikä [+] -painiketta ole. 

![Tienimeä ei voi muuttaa](k55.JPG)

_Kuva 8.3: Lakkautettu tie. Tienimeä ei voi enää muuttaa._

Voimassa olevaa nimeä voi muokata suoraan Tien nimi -kentässä (2) (Kuva 8.2). Tallenna-painike (5) aktivoituu, kun nimeä muokataan. Tallennuksen jälkeen tulee ilmoitus jo olemasta olevasta nimestä (kuva 8.4). Kun nimeä muokkaa, se ei saa uutta loppupäivämäärää eikä uutta riviä muodostu listalle.

![Uuden tienimen muuttaminen](k56.JPG)

_Kuva 8.4: Varmistusilmoitus tienimen muuttamisesta._ 

8.2 Uuden tienimen antaminen tielle, jolla on jo nimi
--------------------------

Tielle on mahdollista antaa uusi nimi esimerkiksi tien alku- tai loppupaikkakunnan muuttuessa. Uusi nimi annetaan painamalla voimassa olevan nimen perässä olevaa [+] -painiketta (3) (kuva 8.2) . Lomakkeelle ilmestyy uusi rivi (1), johon tiedot syötetään (kuva 8.5). Tielle annetaan uusi nimi (2), joka  voi olla enintään 50 merkkiä pitkä, ja alkupäivämäärä (3). Alkupäivämäärä voi olla aikaisintaan edellistä alkupäivämäärää seuraava päivä.
Edellinen nimi saa loppupäivämääräksi (4) automaattisesti uuden nimen alkupäivämäärän. Jos uutta nimeä ei halutakaan tallentaa, painetaan [-]-painiketta (5), jolloin alin rivi poistuu. Uusi nimi tallennetaan Tallenna-painikkeella. Viite varmistaa, halutaanko tielle varmasti antaa uusi nimi. 

![Uuden tienimen muuttaminen](k57.JPG)

_Kuva 8.5: Uuden tienimen antaminen_ 

Kun käyttäjä on tallentanut uuden nimen, muuttuu [-] -painike [+] -painikkeeksi ja vain Tien nimi -kenttää on mahdollista muokata (Ks. tien nimen muokkaaminen, kun tiellä on nimi). 

![Tienimeä ei voi muuttaa](k58.JPG)

_Kuva 8.6: Uusi nimi on tallennettu onnistuneesti. Loppupvm-kenttä ei ole aktiivinen, mutta tienimeä voi muuttaa. Uuden nimen voi tallentaa [+]-painikkeella._

Kirjoitusvirheiden välttämiseksi Viitteessä on päivämäärärajaus. Nimen alkupäivämäärä voi olla enintään 5 vuoden kuluttua nimenantopäivämäärästä. Toisin sanoen, jos käyttäjä antaa 1.3.2019 tielle uuden nimen, voi tien alkupäivämäärä olla viimeistään 28.2.2024.

# 9. Solmut ja liittymät
--------------------------

9.1 Solmujen ja liittymien ylläpitoprosessit
--------------------------

9.1.1 Tieosoitemuutosprojekti
--------------------------

Tieosoitemuutoksista Viite tunnistaa uudet tien tai tieosan alku-tai loppupisteet sekä tietyypin vaihtumiskohdat, joihin se luo solmukohta-aihiot. Tieosoitemuutoksista Viite tunnistaa uudet liittymät, joihin Viite luo liittymäaihiot liittymäkohtineen. 

Viite tunnistaa tieosoitemuutoksessa tarpeettomaksi jäävät solmut, solmukohdat ja liittymät, jolloin se päättää ne. 

Viite myös päivittää nykyisten solmujen solmukohtia ja nykyisten liittymien liittymäkohtia tieosoitemuutoksen mukaisesti.

9.1.2 Solmut ja liittymät 
--------------------------
 
Käyttäjä liittää solmukohta- ja liittymäaihiot solmuun. Tarvittaessa käyttäjä luo liittämisen yhetydessä uuden solmun. Jos jollakin solmun tieosalla ei ole solmukohtaa, järjestelmä laskee solmuun liitettyjen liittymien perusteella näille solmun tieosille solmukohdat.

Käyttäjä voi liittää samaan solmuun useita saman tieosan solmukohtia. Käyttäjä voi myös irrottaa solmukohdan tai liittymän solmusta. Liittymien perusteella laskettuja solmukohtia käyttäjä ei voi irrottaa solmusta, ainoastaan tien tai tieosan alku-tai loppupisteen sekä tietyypin vaihtumiskohdan solmukohdat voi irrottaa solmusta. Irrotetettu solmukohta tai liittymä muuttuu takaisin aihioksi, jonka käyttäjä voi liittää toiseen solmuun.

Jos käyttäjä liittää liittymäaihion solmuun tai irrottaa liittymän solmusta, järjestelmä päivittää liittymien perusteella lasketut solmukohdat.

Jos käyttäjä liittää solmuun solmukohta-aihion, järjestelmä poistaa ko. tieosalta liittymien perusteella lasketun solmukohdan.

Jos käyttäjä irrottaa solmusta solmukohdan, järjestelmä laskee liittymien perusteella solmukohdat niille tieosille, joilla ei ole enää  tien tai tieosan alku-tai loppupisteen sekä tietyypin vaihtumiskohdan solmukohtia.

9.2 Solmujen ja liittymien ylläpitotyökalu
--------------------------

Työkalu avataan klikkaamalla karttanäkymän oikeassa reunassa sijaitsevaa Solmut ja liittymät -painiketta.

![Solmut ja liittymät -työkalun avaaminen](k59.jpg)

_Kuva 9.1: Solmut ja liittymät -työkalun avaaminen._ 

Avautuneessa näkymässä solmutyypit esitetään kartalla symboleilla, joiden selitteet (1) löytyvät vasemmasta reunasta. Sieltä löytyvät myös liittymä-, liittymäaihio- ja solmukohta-aihiosymboleiden selitteet (2) sekä työkalu solmun valintaan (3). 

Näkymän oikeassa reunassa on solmujen hakutoiminto (4).

Hakutoiminnon alapuolella näytetään listat käsittelemättömistä liittymä- ja solmukohta-aihioista (5).

![Solmut ja liittymät -työkalu](k60.jpg)

_Kuva 9.2: Solmut ja liittymät -työkalu._ 

Kun hiiren vie kartalla jonkin solmun päälle, tulee näkyviin infolaatikko (6), joka kertoo kyseisen solmun nimen ja solmutyypin.

Solmun liittymät tulevat näkyviin, kun zoomaa tasolle, jonka mittakaavajanassa on lukema 100 m. Liittymät esitetään sinisillä, numeroiduilla liittymäsymboleilla. Viemällä hiiren liittymän päälle käyttäjä saa esiin infolaatikon (7), jossa näytetään ko. liittymän tieosoite ja sen solmun nimi, johon liittymä on liitetty.

![Solmun ja liittymän -infolaatikot](k61.jpg)

_Kuva 9.3: Solmun ja liittymän infolaatikot._ 

Työkalusta poistutaan klikkaamalla oikean yläkulman Sulje-painiketta. 


9.3 Solmujen haku
--------------------------

Solmujen tietoja voi hakea joko tienumeron tai tienumeron ja tieosien (AOSA, LOSA) perusteella. Käyttäjä syöttää haluamansa arvot kenttiin, painaa Hae solmut -painiketta ja Viite listaa hakua vastaavat solmut formille.

Jos käyttäjä täyttää vain tienumeron, listataan kaikkien kyseisellä tiellä olevien solmujen tiedot. Jos käyttäjä antaa tienumeron ja tieosan, listataan kaikki kyseisen tieosan solmut. Jos käyttäjä on antanut sekä tieosien alku- että loppuosa-arvon, tuodaan näiden välisten tieosien solmujen tiedot. Mikäli käyttäjä antaa tiedon vain AOSA- tai LOSA-kenttään, tulkitaan haun kohdistuvan vain sille tieosalle.

Haun jälkeen karttanäkymä zoomataan niin, että kaikki haetut solmut mahtuvat näytölle.
Formilla näytetään solmuista seuraavat tiedot:
- tieosoite muodossa tie/osa/etäisyys ja solmun nimi
- solmutyyppi
- solmunumero

Lista järjestetään solmujen tieosoitteiden mukaisessa järjestyksessä pienimmästä suurimpaan.

Solmun tieosoiteriviä klikkaamalla voi zoomata kartalla kyseiseen solmuun.

Jos solmulla on samalle tienumerolle kaksi solmukohtaa, niistä käytetään solmukohdan jälkeen olevaa tieosoitetta. Jos solmukohdan tieosoite on hakualueen ulkopuolella, sitä ei näytetä tuloslistauksessa.

Tien viimeinen solmu näytetään aina, samoin ennen epäjatkuvuuskohtaa edeltävä solmu, kun tie jatkuu toisen solmun jälkeen.
Mikäli käyttäjän antama hakuehto tuottaa vastaukseksi yli 50 solmua, Viite antaa ilmoituksen: "Hakusi palauttaa yli 50 kohdetta, rajaa hakua pienemmäksi." Haun tuloksia ei listata.

Tyhjennä tulokset -painike tyhjentää hakutuloslistan.

9.4 Lista liittymä- ja solmukohta-aihioista
--------------------------

Formilla näytetään käyttäjän ELYn/ELYjen mukainen lista liittymä- ja solmukohta-aihioista kasvavassa tieosoitejärjestyksessä. Yhdestä liittymäkohdasta listataan yksi, pienimmällä tienumerolla oleva linkki. Tieosan vaihtumiskohdassa listataan alkavan tieosan 0-kohta.

Liittymäaihion osoite näytetään muodossa tie/ajr/osa/aet ja solmukohta-aihion osoite muodossa tie/osa/ aet.  Osoite on linkki, jota klikkaamalla zoomataan kartalla kyseiseen kohtaan ja päästään käsittelemään aihiota. 


9.5 Nykyisen solmun tietojen katselu/muokkaaminen
--------------------------

Kun käyttäjä klikkaa vasemmassa reunassa sijaitsevaa Solmun valinta -työkalua, muokkaustoiminto aktivoituu ja painikkeen tausta muuttuu siniseksi.  Tämän jälkeen käyttäjä valitsee kartalta solmun, jonka tietoja haluaa katsella tai muokata, ja formille avautuu tiedot solmusta (1), solmukohdista (2) ja liittymistä (3).

![Solmun tiedot, solmukohdat ja liittymät](k62.jpg)

_Kuva 9.4: Solmun tiedot, solmukohdat ja liittymät._ 

Valinnan jälkeen muut kuin käyttäjän valitsema solmu ja sen liittymät näkyvät kartalla himmennettyinä.

Solmun valinta -painike inaktivoituu tietojen tallennuksen tai peruutuksen jälkeen. 

#### Solmun tiedot

Formille avautuvassa nimikentässä voi muokata solmun nimeä. Solmun tyypin saa muutettua valitsemalla uuden tyypin pudotusvalikosta. Kun tyyppi on valittu, sille valitaan uusi alkupäivämäärä. Virheellisen solmutyypin voi korjata ilman päivämäärämuutosta.

Lopuksi käyttäjä tallentaa muutokset tai peruuttaa ne (4).

#### Solmun solmukohtien katselu

Solmun solmukohdista näytetään tienumero, tieosa, etäisyyslukema ja ennen/jälkeen-arvo (5). Solmukohtien tietoja ei voi muokata. 

Solmukohtatietorivin alussa on valintalaatikko solmukohdan irrotusta varten (6). Laskennallisia solmukohtia ei voi irrottaa, ja niiden riveiltä valintalaatikko puuttuu. 

![Solmun solmukohdat](k63.jpg)

_Kuva 9.5: Solmun solmukohdat._ 

#### Solmun liittymien katselu ja liittymänumeron muokkaus

Liittymista näytetään liittymänumero, tienumero, ajorata, tieosa, etäisyyslukema ja ennen/jälkeen-arvo (7). Liittymät näytetään numerojärjestyksessä (8), liittymäkohdat alekkain tieosoitejärjestyksessä.

Käyttäjä voi muokata liittymänumeroa kirjoittamalla uuden liittymänumeron vanhan numeron tilalle (9). Solmulla ei voi olla useita samalla numerolla olevia liittymiä. Viite estää tällaisen tallennuksen. 

![Solmun liittymät](k64.jpg)

_Kuva 9.6: Solmun liittymät._ 

#### Solmun ja liittymien irrotus solmusta

Irrotettava solmukohta ja/tai liittymä valitaan klikkaamalla rivin alussa olevaa valintaruutua. Käyttäjä voi perua valintansa klikkaamalla valintaruutua uudestaan. 

![Varmistus valinnan perumisesta](k65.jpg)

_Kuva 9.7: Varmistus valinnan perumisesta_ 

![Solmukohtien ja liittymien irrotus](k66.jpg)

_Kuva 9.8: Solmukohtien ja liittymien irrotus_ 

Viite valitsee automaattisesti kaikki samassa kohdassa sijaitsevat solmukohdat ja liittymät irrotettaviksi. Käyttäjää pyydetään vahvistamaan irrotus (10).  Muutoksen tallennuksen jälkeen irrotetut solmukohdat/liittymät muuttuvat aihioiksi.

![Tilanne solmukohdan ja liittymien irrotuksen jälkeen](k67.jpg)

_Kuva 9.9: Tilanne solmukohdan ja liittymien irrotuksen jälkeen_ 

9.6 Aihioiden liittäminen solmuun
--------------------------

#### Aihioiden liittäminen uuteen solmuun

Uusi solmu luodaan aihioiden käsittelyn yhteydessä. Käyttäjä valitsee liittymä- tai solmukohta-aihion joko kartalta (1) tai aihiolistalta ja klikkaa “Luo uusi solmu, johon haluat liittää aihiot” -painiketta (2). Hiiren kursori muuttuu nuolesta ristiksi, jolla klikkaamalla asetetaan solmun sijainti kartalla (3).  Sijaintia voi muokata hiirellä vetämällä.

![Aihion liittäminen uuteen solmuun](k68.jpg)

_Kuva 9.10: Aihion liittäminen uuteen solmuun_ 

Tämän jälkeen formille avautuu solmun tiedot. Käyttäjä antaa solmulle nimen, solmutyypin sekä alkupäivämäärän (4). Liittymäaihiolle käyttäjä antaa liittymänumeron (5). Tallenna-painike (6) aktivoituu, kun kaikki pakolliset tiedot on annettu. Tallennuksen jälkeen Viite luo uuden solmun ja formi palautuu Solmut ja liittymät -näytön alkutilaan.

#### Aihioiden liittäminen nykyiseen solmuun

Aihion (7) valinnan jälkeen käyttäjä voi liittää aihion nykyiseen solmuun  “Valitse kartalta solmu, johon haluat liittää aihiot” -painikkeella (8).


![Aihion liittäminen nykyiseen solmuun](k69.jpg)

_Kuva 9.11: Aihion liittäminen nykyiseen solmuun_ 

Painikkeen painamisen jälkeen käyttäjä valitsee kartalta solmun (9) ja klikkaa sitä hiirellä. Solmun tiedot avautuvat formille. Aihioiden tieosoitteet näytetään formilla keltaisina riveinä.

Käyttäjä antaa liittymäaihiolle liittymänumeron (10). Tarvittaessa käyttäjä voi muokata solmun nimeä ja solmutyyppiä (11).   Jos aihion solmutyyppi muuttuu liittämisen jälkeen, solmun alkupäivämäärä on muokattavissa.

Tallenna-painike aktivoituu, kun kaikki pakolliset tiedot on annettu. Tallennuksen jälkeen formi palautuu Solmut ja liittymät -näytön alkutilaan.

9.7 Solmun sijainnin muokkaaminen
--------------------------

Siirrettävä solmu valitaan Solmun valinta -työkalulla. Käyttäjä vetää solmun haluamaansa paikkaan hiirellä. Solmua voi kerralla siirtää enintään 200 metriä.  Solmun saa siirrettyä yli 200 metrin päähän tekemällä sille useamman siirron. Solmun koordinaatit näkyvät formilla, josta sijainnin voi tarkistaa solmua siirrettäessä. Kun solmu on halutulla paikalla, tiedot tallennetaan.  
