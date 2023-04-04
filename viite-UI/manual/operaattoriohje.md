Viite-sovelluksen käyttöohje - operaattoriohje
======================================================

HUOM! 2023-03-28: Ohjeeseen päivitetty Tierekisterin ja VVHn poistumat, poistettu kappaleita poistuneista toiminnallisuuksista, ja päivitetty joitain termejä nykyisiin. Tarkkaa manuaalin korjausta ei kuitenkaan ole tehty. 

VIITE
-----------------------

VIITE on Väyläviraston tieosoitejärjestelmän ylläpitosovellus. Viitteellä hallitaan tieosoitejärjestelmän muutoksia ja se tarjoaa kuvauksen tiestöstä 
Maanmittauslaitoksen keskilinja-aineistoon perustuvan Väyläviraston Keskilinjageometriavaraston (KGV) jäädytetyllä linkkigeometrialla. (Ajantasainen, ns. dynaaminen tieosoiteverkko tulossa, 2023)

Seuraavasta linkistä pääsee Väyläviraston extranet VIITE-sivulle (tällä hetkellä testiextranet käytössä, varsinainen extranet-osoite päivitetään myöhemmin), jossa kerrotaan Viitteen yleiskuvaus ja annetaan tiedotteita käyttäjille. Sivulla ylläpidetään myös dokumentaatiota Viitteestä. 

https://testiextranet.vayla.fi/extranet/web/fi/viite?kategoria=7457637 (testi) 

__Huom! Suosittelemme Firefoxia tai Chromea, kun sovelluksella ylläpidetään Digiroad-tietoja.__

__Huom! Käyttöohjeen kuvia voi klikata isommaksi, jolloin tekstit erottuvat paremmin.__

1 Miten päästä alkuun?
-----------------------

Viite-sovelluksen käyttöä varten tarvitaan Väyläviraston tunnukset (A-, U-, LX-, K- tai L-alkuinen). Mikäli sinulla ei ole tunnuksia, pyydä ne yhteyshenkilöltäsi Väylävirastosta.

Kaikilla Väyläviraston tunnuksilla on pääsy Viite-sovellukseen.

Viite-sovellukseen kirjaudutaan osoitteessa: <a href="https://extranet.vayla.fi/viite/" target="_blank">https://extranet.vayla.fi/viite/</a>.

![Kirjautuminen Viite-sovellukseen.](k1.JPG)

_Kirjautuminen Viite-sovellukseen._

Kirjautumisen jälkeen avautuu karttakäyttöliittymässä katselutila.

![Näkymä kirjautumisen jälkeen.](k2.JPG)

_Karttanäkymä kirjautumisen jälkeen._

Oikeudet on rajattu maantieteellisesti sekä käyttäjän roolin mukaan.

- Ilman erikseen annettuja oikeuksia Väyläviraston tunnuksilla pääsee katselemaan kaikkia tieosoitteita ja käyttämään katselutyökaluja
- Sovelluksen käyttäjällä, jonka Väylätunnukselle on annettu oikeudet muokata tieosoitteita, näkyy Tieosoiteprojekti- ja Tiennimen muokkaus -työkalut

Jos kirjautumisen jälkeen ei avaudu karttakäyttöliittymän katselutilaa, ei kyseisellä tunnuksella ole pääsyä Väyläviraston extranettiin. Tällöin tulee ottaa yhteyttä Väylävirastossa tai Elyssä omaan yhteyshenkilöön.

1.1 Mistä saada opastusta?
--------------------------

Viite-sovelluksen käytöstä voi kysyä järjestelmävastaavalta: Jani.Lehenberg@vayla.fi.
(Viitellä oli aiemmin operaattoritoimintaa, tässä roolissa oli lähinnä Janne Grekula janne.grekula@cgi.com.)

####Ongelmatilanteet####

Sovelluksen toimiessa virheellisesti (esim. kaikki aineistot eivät lataudu oikein) toimi seuraavasti:

- Lataa sivu uudelleen näppäimistön F5-painikkeella.
- Tarkista, että selaimestasi on käytössä ajan tasalla oleva versio ja selaimesi on Mozilla Firefox tai Chrome
- Jos edellä olevat eivät korjaa ongelmaa, ota yhteyttä jani.lehenberg@vayla.fi <!--aiemmin operaattorina janne.grekula@cgi.com-->


2 Perustietoja Viite-sovelluksesta
--------------------------


2.1 Viite-sovelluksen yhteydet muihin järjestelmiin
--------------------------

![Viite_jarjestelmat_ja_liittymat](Viite_jarjestelmat_ja_liittymat.JPG)

_Viite-sovelluksen yhteydet muihin järjestelmiin ja palveluihin. KUVA VANHENTUNUT; VVH->KGV, JA TIEREKISTERI POISTUNUT_

##Keskilinjageometriavarasto (KGV)##

Viite-sovelluksessa pohja-aineistona oleva geometria tulee Väyläviraston Keskilinjageometriavarastosta (KGV). KGV:oon ladataan päivittäin Maanmittauslaitokselta päivitykset Maastotietokannan (MTK) keskilinja-aineistoon, jolloin myös Viite-sovellukselle olisi käytössä uusi keskilinjageometria joka päivä. Tällä hetkellä (2023-03) Viite käyttää jäädytettyä rajapintaa, jonka aineisto päivitetään puolen vuoden välein.

##Digiroad (ent. OTH): Ominaisuustietojen hallinta##

Digiroadissa on käytössä KGV:n tarjoama keskilinjageometria.

Lisäksi Digiroad-sovellus hyödyntää Viitteen tieosoitetietoja, jotka näytetään Digiroadissa tielinkin ominaisuustietona.

##Väyläviraston Tierekisteri##

_Tierekisteri on poistunut, sitä korvaa osittain Velho._

##Velho##

_TODO_

2.2 Tiedon rakentuminen Viite-sovelluksessa
--------------------------

Viite-sovelluksessa tieosoiteverkko piirretään KGV:n tarjoaman Maanmittauslaitoksen keskilinja-aineiston päälle. Maanmittauslaitoksen keskilinja-aineisto muodostuu tielinkeistä. Tielinkki on tien, kadun, kevyen liikenteen väylän tai lauttayhteyden keskilinjageometrian pienin yksikkö. Tieosoiteverkko piirtyy geometrian päälle tieosoitesegmentteinä _lineaarisen referoinnin_ avulla. 

Tielinkki on Viite-sovelluksen lineaarinen viitekehys, eli sen geometriaan sidotaan tieosoitesegmentit. Kukin tieosoitesegmentti tietää mille tielinkille se kuuluu (tielinkin ID) sekä kohdan, josta se alkaa ja loppuu kyseisellä tielinkillä. Tieosoitesegmentit ovat siten tielinkin mittaisia tai niitä lyhyempiä tieosoitteen osuuksia. Käyttöliittymässä kuitenkin pienin valittavissa oleva osuus on tielinkin mittainen (kts. luvut 4.1 ja 7.1).

Kullakin tieosoitesegmentillä on lisäksi tiettyjä sille annettuja ominaisuustietoja, kuten tienumero, tieosanumero ja ajoratakoodi. Tieosoitesegmenttien ominaisuustiedoista on kerrottu tarkemmin kohdassa "Tieosoitteen ominaisuustiedot".

![Kohteita](k9.JPG)

_Tieosoitesegmenttejä (1) ja muita tielinkkejä (2) Viitteen karttaikunnassa._

Tieosoitesegmentit piirretään Viite-sovelluksessa kartalle erilaisin värein (kts. kohta 4. Tieosoiteverkon katselu). Muut tielinkit, jotka eivät kuulu tieosoiteverkkoon, piirretään kartalle harmaalla. Näitä ovat esimerkiksi tieosoitteettomat kuntien omistamat tiet, ajopolut, ajotiet jne.

Palautteet geometrian eli tielinkkien virheistä voi laittaa Maanmittauslaitokselle, maasto@maanmittauslaitos.fi. Mukaan selvitys virheestä ja sen sijainnista (esim. kuvakaappaus).

3. Karttanäkymän muokkaus
--------------------------

![Karttanäkymän muokkaus](k3.JPG)

_Karttanäkymä._

####Kartan liikutus####

Karttaa liikutetaan raahaamalla.

####Mittakaavataso####

Kartan mittakaavatasoa muutetaan joko hiiren rullalla, tuplaklikkaamalla, Ctrl+piirto (alue) tai mittakaavapainikkeista (1). Mittakaavapainikkeita käyttämällä kartan keskitys säilyy. Hiiren rullalla, tuplaklikkaamalla tai Shift+piirto (alue) kartan keskitys siirtyy kursorin kohtaan.  Käytössä oleva mittakaavataso näkyy kartan oikeassa alakulmassa (2).

####Kohdistin####

Kohdistin (3) kertoo kartan keskipisteen. Kohdistimen koordinaatit näkyvät karttaikkunan oikeassa alakulmassa(4). Kun kartaa liikuttaa eli keskipiste muuttuu, päivittyvät koordinaatit. Oikean alakulman valinnan (5) avulla kohdistimen saa myös halutessaan piilotettua kartalta.

####Merkitse piste kartalla####

Merkitse-painike (6) merkitsee sinisen pisteen kartan keskipisteeseen. Merkki poistuu vain, kun merkitään uusi piste kartalta.

####Taustakartat####

Taustakartaksi voi valita vasemman alakulman painikkeista maastokartan, ortokuvat tai taustakarttasarjan.

####Hakukenttä####

Käyttöliittymässä on hakukenttä (8), jossa voi hakea koordinaateilla ja katuosoitteella tai tieosoitteella. Haku suoritetaan kirjoittamalla hakuehto hakukenttään ja klikkaamalla Hae. Hakutulos tulee listaan hakukentän alle. Hakutuloslistassa ylimpänä on maantieteellisesti kartan nykyistä keskipistettä lähimpänä oleva kohde. Mikäli hakutuloksia on vain yksi, keskittyy kartta automaattisesti haettuun kohteeseen. Jos hakutuloksia on useampi kuin yksi, täytyy listalta valita tulos, jolloin kartta keskittyy siihen. Tyhjennä tulokset -painike tyhjentää hakutuloslistan.

Tieosoitteella haku: Tieosoitteesta hakukenttään voi syöttää koko osoitteen tai osan siitä. Esim. 2 tai 2 1 150. (Varsinainen tieosoitehaku tieosoitteiden ylläpidon tarpeisiin toteutetaan myöhemmin)

Koordinaateilla haku: Koordinaatit syötetään muodossa "pohjoinen (7 merkkiä), itä (6 merkkiä)". Koordinaatit tulee olla ETRS89-TM35FIN -koordinaattijärjestelmässä. Esim. 6975061, 535628.

Katuosoitteella haku: Katuosoitteesta hakukenttään voi syöttää koko ositteen tai sen osan. Esim. "Mannerheimintie" tai "Mannerheimintie 10, Helsinki".


4. Tieosoiteverkon katselu
--------------------------

Geometrialtaan yleistetty tieosoiteverkko tulee näkyviin kun zoomaa tasolle jossa mittakaavajanassa on lukema 5 km. Tästä tasosta ja sitä lähempää piirretään kartalle valtatiet, kantatiet, seututiet, yhdystiet ja numeroidut kadut. Yleistämätön tieverkko piirtyy mittakaavajanan lukemalla 2 km. 100 metriä (100 metrin mittakaavajanoja on kaksi kappaletta) suuremmilla mittakaavatasoilla tulevat näkyviin kaikki tieverkon kohteet.

Tieosoiteverkko on värikoodattu tienumeroiden mukaan. Vasemman yläkulman selitteessä on kerrottu kunkin värikoodin tienumerot. Lisäksi kartalle piirtyvät etäisyyslukemasymbolit, eli ne kohdat, joissa vaihtuu tieosa tai ajoratakoodi. Tieverkon kasvusuunta näkyy kartalla pisaran mallisena nuolena.

![Mittakaavajanassa 2km](k4.JPG)

_Mittakaavajanassa 2 km._

![Mittakaavajanassa 100 m](k5.JPG)

_Mittakaavajanassa 100 m._

Tieosoitteelliset kadut erottuvat kartalla muista tieosoitesegmenteistä siten, että niiden ympärillä on musta väritys.

![Tieosoitteellinen katu](k16.JPG)

_Tieosoitteellinen katu, merkattuna mustalla värityksellä tienumeron värityksen lisäksi._

Kun hiiren vie tieosoiteverkon päälle, tulee kartalle näkyviin "infolaatikko", joka kertoo kyseisen tieosoitesegmentin tienumeron, tieosanumeron, ajoratakoodin, alkuetäisyyden ja loppuetäisyyden.

![Hover](k35.JPG)

_Infolaatikko, kun hiiri on viety tieosoitesegmentin päälle._

4.1 Kohteiden valinta
--------------------------
Kohteita voi valita klikkaamalla kartalta. Klikkaamalla kerran, sovellus valitsee kokonaisen tieosan. Valittu tieosa korostuu kartalla (1), ja sen tiedot tulevat näkyviin karttaikkunan oikeaan laitaan ominaisuustietonäkymään (2).

![Tieosan valinta](k6.JPG)

_Tieosan valinta._

Tuplaklikkaus valitsee yhden tielinkin mittaisen osuuden tieosoitteesta. Valittu osuus korostuu kartalla (3), ja sen tiedot tulevat näkyviin karttaikkunan oikeaan laitaan ominaisuustietonäkymään (4).

![Tieosoitesegmentin valinta](k7.JPG)

_Tielinkin mittaisen osuuden valinta._


##Tieosoitteen ominaisuustiedot##

Tieosoitteilla on seuraavat ominaisuustiedot:

| Ominaisuustieto       | Kuvaus                                                                                             | Sovellus muodostaa |
|-----------------------|----------------------------------------------------------------------------------------------------|--------------------|
| Muokattu viimeksi*    | Muokkaajan käyttäjätunnus ja tiedon muokkaushetki.                                                 | X                  |
| Linkkien lukumäärä    | Niiden tielinkkien lukumäärä, joihin valinta  kohdistuu.                                           | X                  |
| Tienumero             | Tieosoiteverkon mukainen tienumero.***                                                             |                    |
| Tieosanumero          | Tieosoiteverkon mukainen tieosanumero.***                                                          |                    |
| Ajorata               | Tieosoiteverkon mukainen ajoratakoodi.***                                                          |                    |
| Alkuetäisyys**        | Tieosoiteverkon etäisyyslukemien avulla laskettu alkuetäisyys.***                                  | X                  |
| Loppuetäisyys**       | Tieosoiteverkon etäisyyslukemien avulla laskettu loppuetäisyys.***                                 | X                  |
| ELY                   | Väyläviraston ELY-numero.                                                                          | X                  |
| Hallinnollinen luokka | Muodostetaan Maanmittauslaitoksen hallinnollinen luokka -tiedoista, kts. taulukko alempana. Jos valitulla tieosalla on useita hallinnollisia luokkia, ne kerrotaan ominaisuustietotaulussa pilkulla erotettuna. | X                  |
| Jatkuvuus             | Tieosoiteverkon mukainen jatkuvuus-tieto.***                                                       | X                  |

*) Muokattu viimeksi -tiedoissa vvh_modified tarkoittaa, että muutos on tullut Maanmittauslaitokselta joko geometriaan tai geometrian ominaisuustietoihin. Muokattu viimeksi -päivät ovat kaikki vähintään 29.10.2015, koska tuolloin on tehty Maanmittauslaitoksen geometrioista alkulataus ("jäädytys") VVH:n (KGV:n edeltäjä) tietokantaan.

**) Tieosoiteverkon etäisyyslukemat (tieosan alku- ja loppupisteet sekä ajoratakoodin vaihtuminen) määrittelevät mitatut alku- ja loppuetäisyydet. Etäisyyslukemien välillä alku- ja loppuetäisyydet lasketaan tieosoitesegmenttikohtaisesti Viite-sovelluksessa.

***) Alkuperäisenä lähtöaineistona Tierekisterin tieosoitteet 2.1.2017.

__Hallinnollinen luokka Viite-sovelluksessa__

Viite saa hallinnollinen luokka -tiedon automaattisesti Maanmittauslaitoksen aineiston pohjalta.
Käyttäjä voi muokata sitä tieosoiteprojekteissa.

| Hallinnollinen luokka                                            |
|------------------------------------------------------------------|
| Valtio:     MML:n hallinnollinen luokka arvolla 1 = Valtio       |
| Kunta:      MML:n hallinnollinen luokka arvolla 2 = Kunta        |
| Yksityinen: MML:n hallinnollinen luokka arvolla 3 = Yksityinen - tai MML:lla ei tiedossa hallinnollista luokkaa -> 3 = Yksityinen |

Palautteet hallinnollisen luokan virheistä voi toimittaa Maanmittauslaitokselle osoitteeseen maasto@maanmittauslaitos.fi. Mukaan selvitys virheestä ja sen sijainnista (kuvakaappaus tms.).

##Kohdistaminen tieosoitteeseen tielinkin ID:n avulla##

Kun kohdetta klikkaa kartalla, tulee selaimen osoiteriville näkyviin valitun kohteen tielinkin ID. Osoiterivillä olevan URL:n avulla voi myös kohdistaa käyttöliittymässä ko. tielinkkiin. URL:n voi esimerkiksi lähettää toiselle henkilölle sähköpostilla, jolloin hän pääsee samaan paikkaan käyttöliittymässä helposti.

Esimerkiksi: https://viite.vaylapilvi.fi/#linkProperty/025a3c1c-4ca3-4db6-845b-413db2eed009:1 näkyy kuvassa osoiterivillä (5). 
025a3c1c-4ca3-4db6-845b-413db2eed009:1 on tielinkin ID, joka sislätää sekä linkin varsinaisen id:n (viivoilla pätkitty pitkä heksadesimaalinumero), että sen versionumeron (tässä '1').


![Kohdistaminen tielinkin ID:llä](k8.JPG)

_Kohdistaminen tielinkin ID:llä._

5 
--------------------------

(Kappale 5 poistettu (2023-03); toimintoa ei ole enää Viiteessä)

6 Rakenteilla olevat tielinkit ja niiden tieosoitteet
--------------------------

Rakenteilla olevilla kohteilla tarkoitetaan sellaisia tielinkkejä, joiden valmiusaste/status on Maanmittauslaitoksella "Rakenteilla". 

Rakenteilla olevat, tieosoitteistettavat kohteet näkyvät käyttöliittymässä oranssi-mustalla raidoituksella (1). Näillä kohteilla on hallinnollisen luokan arvo 1=Valtion omistama, mutta niiltä puuttuu tieosoite eli kohteet ovat tuntemattomia.

![Tuntemattoman rakenteilla oleva kohde](k13.JPG)

_Tuntematon rakenteilla oleva kohde._

Jos rakenteilla olevalle kohteelle on jo annettu tieosoite, se näkyy käyttöliittymässä kuten muutkin tieosoitteistetut tielinkit (3) ja tieosoitetiedot näkyvät myös ominaisuustietotaulussa (4).

![Tieosoitteistettu rakenteilla oleva kohde](k15.JPG)

_Pisaraliittymä on rakenteilla, mutta koska se on jo saanut tieosoiteen, se näkyy käyttöliittymässä kuten muutkin tieosoitteistetut tielinkit._

Muut rakenteilla olevat kohteet näkyvät käyttöliittymässä harmaa-mustalla raidoituksella (2). Näiden hallinnollisen luokan arvo on jotain muuta, kuin 1=Valtio.

![Rakenteilla oleva kohde](k14.JPG)

_Rakenteilla oleva kohde, jonka hallinnollisen luokan arvo on jokin muu kuin 1=Valtio._


7 Automatiikka Viite-sovelluksessa
--------------------------
Viite-sovelluksessa on muutamia automatiikan tekemiä yleistyksiä tai korjauksia. Automatiikka ei muuta mitään sellaisia tietoja, jotka muuttaisivat varsinaisesti tieosoitteita. Automatiikan tekemät muutokset liittyvät siihen, että tieosoiteverkkoa ylläpidetään keskilinjageometrian päällä, ja tuon keskilinjageometrian ylläpidosta vastaa Maanmittauslaitos. Tietyt automaattiset toimenpiteet helpottavat tieosoiteverkon ylläpitäjää varsinaisessa tieosoiteverkon hallinnassa.

__Huom! Automatiikka ei koskaan muuta tieosan mitattua pituutta. Arvot tieosien ja ajoratojen vaihtumiskohdissa pysyvät aina ennallaan automatiikan tekemien korjausten yhteydessä.__

##7.1 Tieosoitesegmenttien yhdistely tielinkin mittaisiksi osuuksiksi##

Kun käyttäjä valitsee kartalla kohteita tuplaklikkaamalla (luku 4.1), on pienin valittava yksikkö tielinkin mittainen osuus tieosoiteverkosta, koska tielinkki on pienin mahdollinen yksikkö Maanmittauslaitoksen ylläpitämällä linkkiverkolla.

Tätä varten järjestelmä tekee automaattista yhdistelyä:

- Ne kohteet, joiden tielinkin ID, tie, tieosa, ajorata ja alkupäivämäärä ovat samoja (=tieosoitehistoria yhtenevä), on yhdistetty tietokannassa yhdeksi tielinkin mittaiseksi tieosoitesegmentiksi
- Ne kohteet, joiden tielinkin ID, tie, tieosa, ajorata ovat samoja, mutta alkupäivämäärä ei ole sama (=tieosoitehistoria erilainen) ovat tietokannassa edelleen erillisiä kohteita, mutta käyttöliittymässä ne ovat valittavissa vain tielinkin mittaisena osuutena. Tällä varmistetaan, että käyttöliittymä toimii käyttäjän kannalta loogisesti, eli aina on valittavissa tielinkin mittainen osuus, mutta tieosoitehistoria säilytetään tietokantatasolla.

##7.2 Tieosoitesegmenttien automaattinen korjaus jatkuvasti päivittyvällä linkkigeometrialla##

(Poistettu, Viiteen linkkigeometria jäädytetty (2023-03))


8 
--------------------------

(Kappale 8 poistettu (2023-03); toimintoa ei ole enää Viiteessä)

9 
--------------------------

(Kappale 8 poistettu (2023-03); toimintoa ei ole enää Viiteessä)

10 Tieosoiteprojektin tekeminen
--------------------------

Uuden tieosoiteprojektin tekeminen aloitetaan klikkaamalla painiketta Tieosoiteprojektit (1) ja avautuvasta ikkunasta painiketta Uusi tieosoiteprojekti (2).

![Uusi tieosoiteprojekti](k17.JPG)

_Tieosoiteprojektit -painike ja Uusi tieosoiteprojekti -painike._

Näytön oikeaan laitaan avautuu lomake tieosoiteprojektin perustietojen täydentämiseen. Jos käyttäjä ei ole muokkaustilassa, sovellus siirtyy tässä vaiheessa automaattisesti muokkaustilaan.

![Uusi tieosoiteprojekti](k18.JPG)

_Tieosoiteprojektin perustietojen lomake._

Pakollisia tietoja ovat nimi ja projektin muutosten voimaantulopäivämäärä, jotka on merkattu lomakkeelle oranssilla (3). Projektiin ei ole pakko täydentää yhtään tieosaa. Lisätiedot -kenttään käyttäjä voi halutessaan tehdä muistiinpanoja tieosoiteprojektista. Tiedot tallentuvat painamalla Jatka toimenpiteisiin -painiketta (4). Myös Poistu-painikkeesta Viite varmistaa haluaako käyttäjä tallentaa projektiin tehdyt muutokset.

![Uusi tieosoiteprojekti](k19.JPG)

_Tieosoiteprojektin perustietojen täyttäminen._

Projektin tieosat lisätään täydentämällä niiden tiedot kenttiin tie, aosa, losa ja painamalla painiketta Varaa (5). __Kaikki kentät tulee täytää aina, kun haluaa varata tieosan!__ 

Varaa -painikkeen painamisen jälkeen tieosan tiedot tulevat näkyviin lomakkeelle.

![Uusi tieosoiteprojekti](k22.JPG)

_Tieosan tiedot lomakkeella Lisää -painikkeen painamisen jälkeen._

Tieosoiteprojekti tallentuu automaattisesti painikkeesta Jatka toimenpiteisiin, jolloin tiedot tallentuvat tietokantaan ja sovellus siirtyy toimenpidenäytölle. Varaamisen yhteydessä Viite zoomaa kartan varatun tieosan alkuun. Poistu-painikkeesta projekti suljetaan ja käyttäjältä varmistetaan, halutaanko tallentamattomat muutokset tallentaa. Projektiin pääsee palaamaan Tieosoiteprojektit -listan kautta. 

Käyttäjä voi poistaa varattuja tieosia klikkaamalla ruksia tieosan oikealla puolella, Projektiin valitut tieosat-listalta. Mikäli tieosille ei ole tehty muutoksia eli ne on pelkastään varattu projektiin, varaus poistetaan projektista. Jos tieosille on tehty muutoksia, Viite kysyy käyttäjältä varmistuksen poistosta. Tällöin kaikki muutokset menetetään.



![Uusi tieosoiteprojekti](k20.JPG)

_Kun tieosa on varattu projektiin Viite zoomaa kartan siten että tieosa näkyy kartalla kokonaisuudessaan._  

Varauksen yhteydessä järjestelmä tekee varattaville tieosille tarkistukset:

- Onko varattava tieosa olemassa projektin voimaantulopäivänä
- Onko varattava tieosa vapaana, eikä varattuna mihinkään toiseen projektiin

Virheellisistä varausyrityksistä järjestelmä antaa asianmukaisen virheilmoituksen. Alla olevassa kuvissa on erilaisista virheilmoituksista, joiden mukaan käyttäjän tulee korjata varausta. __Käyttäjän tulee huomioida, että varauksen yhteydessä kaikki kentät (TIE, AOSA, LOSA) tulee täyttää, tai käyttäjä saa virheilmoituksen!__

![Uusi tieosoiteprojekti](k21.JPG)

_Yritetty luoda samalla tieosavarauksella tie 71, osa 1 projekti uudelleen. 
Tieosa on kuitenkin jo varattuna aiemmin luotuun projektiin nimeltä "Tieosoitemuutos tie 71 osa 1"._

![Uusi tieosoiteprojekti](k23.JPG)

_Tietä ei ole olemassa._

![Uusi tieosoiteprojekti](k24.JPG)

_Tieosaa ei ole olemassa._

![Uusi tieosoiteprojekti](k25.JPG)

_Tieosaa ei ole olemassa._

![Uusi tieosoiteprojekti](k40.JPG)

_Ilmoitus, jos varattava tieosa ei ole voimassa päivänä, jolloin projekti tulee voimaan._

10.1 Olemassa olevan tieosoiteprojektin avaaminen Tieosoiteprojektit -listalta
--------------------------

Tallennetun tieosoiteprojektin saa auki Tieosoiteprojektit -listalta painamalla Avaa -painiketta. Avaamisen yhteydessä sovellus zoomaa kartan paikkaan, jossa käyttäjä on viimeimmäksi tallentanut toimenpiteen. Mikäli toimenpiteitä ei ole tehty, karttanäkymä rajautuu siten, että kaikki varatut aihiot näkyvät karttanäkymässä

Tieosoiteprojektit -listalla näkyvät kaikkien käyttäjien projektit. Projektit on järjestetty ELY-koodien mukaiseen järjestykseen ja niiden sisällä projektin nimen ja käyttäjätunnuksen mukaiseen järjestykseen. Projektin tekijän käyttäjätunnus näkyy myös projektilistauksessa.

![Uusi tieosoiteprojekti](k26.JPG)

_Tieosoiteprojektit -listaus._

11 Muutosilmoitusten tekeminen tieosoiteprojektissa
--------------------------

Tieosoiteprojektissa on mahdollista tehdä seuraavia muutosilmoituksia:


- lakkautus (tieosoitteen lakkautus) 
- uusi (lisätään uusi tieosoite osoitteettomalle linkille) 
- ennallaan (osa tieosoitteesta säilyy ennallaan, kun osaa siitä muutetaan)
- siirto (tieosan alkuetäisyys- ja loppuetäisyysarvot päivitetään, kun muulle osalle tieosaa tehdään muutos)
- numeroinnin muutos (kokonaisen tieosan tienumeron ja/tai tieosanumeron voi muuttaa manuaalisesti) 
- kääntö (tieosoitteen kasvusuunnan kääntö)
- etäisyyslukeman muutos (etäisyyslukeman loppuarvon voi syöttää tieosalle manuaalisesti)
- ELY koodin, jatkuvuuden ja hallinnollisen luokan muutos

Tässä sekä seuraavissa kappaleissa kuvataan muutosilmoitusten teko Viitteessä.


Tieosoiteprojektissa muutostoimenpiteitä pääsee tekemään klikkaamalla Jatka toimenpiteisiin-painiketta tieosoitemuutosprojektin perustietojen lomakkeella. Tämän jälkeen sovellus muuttaa varatut tieosat muokattaviksi kohteiksi ja ne näkyvät avautuvassa karttanäkymässä keltaisella korostettuna (1). Mikäli toimenpiteenä lisätään uusi tieosoite, eli tieosia ei ole varattu projektiin, kartalta ei valikoidu mitään ennen käyttäjän tekemää valintaa. Tämä tarkennetaan tulevissa toimenpiteitä kuvaavissa kappaleissa.

Projektitilassa, vain projektiin varattuja tieosia, tuntemattomia tielinkkejä, muun tieverkon linkkejä tai suunnitelmalinkkejä (suravage-linkkejä), voi valita projektissa klikkaamalla kartalta. Suunnitelmalinkit saa pois piirrosta ja piirtoon sivun alapalkissa olevasta valinnasta. Ne ovat oletuksena piirrossa. Tieverkon tieosoitetietoja voi katsella kartalla viemällä hiiren tieosoitelinkin päälle. Tällöin tielinkin infolaatikko tulee näkyviin. 
Projektin nimen vieressä on sininen kynäikoni (2), josta pääsee projektin perustietojen lomakkeelle muokkaamaan projektin tietoja. Lisäksi oikeassa yläkulamssa on Poistu projektista -linkki (3), josta pääsee Viitteen alkutilaan. Viite tarkistaa haluaako käyttäjä tallentaa muutokset.

![Aihio](k36.JPG)

_Projektissa muokattavissa olevat varatut tieosat näkyvät kartalla keltaisella värillä ja suuntanuolet ovat tien alkuperäisen värin mukaiset (siniset). Projektin nimi on "Esimerkki-projekti", joka näkyy oikeassa yläkulmassa._

Projektin muutosilmoitukset tallentuvat projektin yhteenvetotauluun, jonka voi avata näkyviin toimenpidenäkymän alaoikeasta laidasta sinisestä painikkeesta (4). Yhteenvetotaulun toiminta on kuvattu tarkemmin kappaleessa 11.2. Lisäksi kaikkien projektien muutostiedot voidaan 
hyväksyä tieosoiteverkolle klikkaamalla vihreää Hyväksy tieosoiteverkolle -painiketta (5) sivun alaoikealla. Muutosilmoituksen lähettäminen on kuvattu kappaleessa 11.3. 

Kun keltaista, muokattavaa kohdetta kertaklikkaa kartalla, muuttuu valittu osuus vihreäksi ja oikeaan laitaan tulee alasvetovalikko, josta voi valita kohteelle tehtävän muutosilmoituksen (esim. lakkautus). Kerran klikkaamalla valitaan kartalta homogeeninen jakso (= sama tienumero, tieosanumero, ajoratakoodi, hallinnollinen luokka ja jatkuvuus). Tuplaklikkaus valitsee yhden tieosoitesegmentin verran (tielinkin mittainen osuus). Kun halutaan valita vain osa tieosan linkeistä, tuplaklikataan ensimmäistä linkkiä ja seuraavat linkit lisätään valintaan CTRL+klikkauksella samalta tieosalta. Samalla tavalla voi myös poistaa yksittäisiä linkkejä valinnasta. 

![Valittu kohde](k37.JPG)

_Kun keltaista, muokattavissa olevaa kohdetta klikataan, muuttuu tieosa vihreäksi ja oikeaan laitaan tulee näkyviin valikko, jossa on tieosoitemuutosprojektin mahdolliset muutosilmoitukset._

Muutokset tallennetaan oikean alakulman Tallenna-painikkeesta. Ennen tallennusta, voi muutokset perua Peruuta-painikkeesta, jolloin Viite palaa edeltävään vaiheeseen.

Jos käyttäjä on jo tehnyt projektissa muutoksia tieosoitteille, ne tulevat näkyviin lomakkeelle klikatessa kyseistä tielinkkiä, jolle muutokset on tehty. Esimerkiksi, mikäli tieosalle on toteutettu Lakkautus, tieosa valittaessa sen tiedot lakkautuksesta ilmestyvät lomakkeelle ja tieosalle on mahdollista tehdä toinen toimenpide. Mahdolliset uudet toimenpidevaihtoehdot kunkin toimenpiteen tallentamisen jälkeen, on kuvattu seuraavissa kappaleissa, joissa kerrotaan kunkin toimenpiteen tekemisestä tarkemmin. 

Selite projektitilassa on erilainen kuin katselutilassa. Projektitilan selite kuvaa linkkiverkkoon tehdyt toimenpiteet kun taas katselutilan selite kuvaa tieluokitusta.



11.1 Muutosilmoitusten kuvaukset
--------------------------

11.1.1 Lakkautus
--------------------------
Kun halutaan lakkauttaa joko tieosia, tieosa tai osa tieosasta, tulee tarvittavat tieosat varata projektiin. Varaaminen tehdään, kuten kpl 10. esitetty, eli syötetään projektitietojen lomakkeelle haluttu tienumero ja tieosa sekä painetaan Lisää-painiketta.
Tämän jälkeen Jatka toimenpiteisiin-painiketta, jolla siirrytään toimenpidelomakkeelle tekemään tieosoitemuutosta. Toimenpidelomakkeella valitaan kartalta projektiin varattu tieosa, -osat tai tarvittavat linkit valitusta tieosasta. Ne muuttuvat valittuna vihreäksi. (Shift+tuplaklikkaus-painalluksella voi lisätä yksittäisiä linkkejä valintaan tai poistaa yksittäisiä linkkejä valinasta.) Toimenpide-lomakkeelle tulee tiedot valituista linkeistä sekä pudotusvalikko, josta valitaan Lakkautus. Tämän jälkeen tallennetaan muutos projektiin. Lakkautettu linkki värjäytyy mustaksi ja sen tiedot päivittyvät yhteenvetotaulukkoon, jonka voi avata sinisestä Avaa projektin yhteenvetotaulukko-painikkeesta. Yhteenvetotaulukon toiminta on kuvattu kappaleessa 11.2. Mikäli on lakkautettu vain osa tieosan linkeistä, tulee tieosan muut kuin lakkautetut linkit myös käsitellä joko ennallaan- tai siirto-toimenpiteillä, riippuen tilanteesta. Kun tarvittavat muutoset projektissa on tehty, 
muutostiedot voi hyväksyä tieosoiteverkolle painamalla Hyväksy tieosoiteverkolle-painiketta. 

11.1.2 Uusi
--------------------------

Toimenpiteellä määritetään uusi tieosoite tieosoitteettomille linkeille. Tieosoitteettomia muun tieverkon linkkejä,
jotka piirtyvät kartalle harmaina, tai suravage-linkkejä, voi valita kerta- tai tuplaklikkauksella, kuten muitakin tielinkkejä. Tuplaklikkaus valitsee yhden tielinkin ja shit+tuplaklikkauksella voi lisätä tai poistaa valintaan linkkejä yksi kerrallaan. Kertaklikkaus valitsee homogeenisen jakson, jossa käytetään KGV:n tienumeroa ja tieosanumeroa. Tienumeron tai tieosanumeron puuttuessa valinnassa käytetään tienimeä. CTRL+klikkauksella voi lisätä aiempaan valintaan koko homogeenisen jakson.

Valitut tielinkit näkyvät kartalla vihreällä korostettuna. Kun valitaan Toimenpiteet-alasvetovalikosta 'Uusi' (1) lomakkeelle avautuvat kentät uuden tieosoitteen tiedoille (2). Jos valitulla tieosuudella on jo olemassa KGV:ssa tienumero ja tieosanumero, ne esitäyttyvät kenttiin automattisesti.

![Uusi tieosoite](k43.JPG)

_Kun toimenpidevalikosta valitaan 'Uusi', oikeaan laitaan ilmestyy näkyviin kentät uuden tieosoitteen syöttämistä varten._ 

Hallinnollista luokkaa voi muokata pudotusvalikosta (3). (Epä-)Jatkuvuus-arvo määräytyy ensimmäisellä tallennuskerralla automaattisesti jatkuvaksi (5 Jatkuva). Muutokset tallennetaan Tallenna-painikkeella (4). Ennen tallennusta, muutokset voi perua Peruuta-painikkeesta. 

Huom: Mikäli jatkuvuutta täytyy muokata, esimerkiksi jos kyseessä on tien loppu, se tulee tehdä seuraavasti. Käyttäjän tulee klikata viimeinen "Uusi" toimenpiteellä käsitelty linkki tieosan lopusta aktiiviseksi, jossa tien loppu sijaitsee. Lomakkeelle tulee tiedot linkin osoitteesta ja sille tehdystä toimenpiteestä. Nyt linkin jatkuvuuskoodin muokkaaminen on mahdollista ja oikea koodi, esimerkiksi 1 Tien loppu, valitaan pudotusvalikosta ja tallennetaan. Päivitetty tieto näkyy myös yhteenvetotaulukossa tallennuksen jälkeen.   


Käyttöliittymä varoittaa virheilmoituksella jos uusi tieosoite on jo olemassa projektin alkupäivänä tai se on varattuna toisessa tieosoiteprojektissa.

![Tieosoite on jo olemassa](k44.JPG)

_Tieosoite on jo olemassa projektin alkupäivänä._

![Kasvusuunnan vaihto](k47.JPG)

_Valittuna olevan uuden tieosoitteen vaikutussuntaa vaihtuu lomakkeen 'Käännä vaikutussuunta'-nappulasta._


Uuden tieosoitteen linkit piirtyvät kartalle pinkillä värillä (2). Tieosan alku- ja loppupisteisiin sijoitetaan automaattisesti etäisyyslukema-symbolit. Viite laskee uudelle tieosuudelle automaattisesti myös linkkien m-arvot käyttäen KGV:n tietoja. Uudelle tieosoitteelle määrittyy aluksi satunnainen kasvusuunta, joka näkyy kartalla pinkkien nuolien suunnasta.

![Uusi tieosoite pinkilla](k46.JPG)

_Uuden tieosoitteen linkit piirtyvät kartalle pinkillä. Tieosan voi valita klikkaamalla, jolloin se korostuu vihreällä._


Tallennettuun tieosoitteeseen voi jatkaa uusien linkkien lisäämistä vaiheittain. Ensin valitaan tallennetun tieosan jatkeeksi seuraava linkki ja sitten valitaan lomakkeelta toimenpide "uusi" ja annetaan linkeille sama tieosoite (TIE= tienumero, OSA=tieosanumero, AJR=ajoratakoodi). ELY ja Jatkuu arvot Viite täyttää automaattisesti. Tallennetaan lisäykset. Projektin voi myös tallentaa, sulkea ja jatkaa lisäystä samaan tieosoitteeseen myöhemmin. Kasvusuunta lisätylle osuudelle määräytyy aiemmin osoitteistettujen linkkien mukaan ja sitä voi edelleen kääntää Käännä kasvusuunta-painikkeella. M-arvot päivittyvät koko tieosalle, jolle on annettu sama tieosoite.


__Uuden kiertoliittymän alkupaikan muuttaminen__

Jos Uusi-toimenpiteellä tieosoitteistetulla kiertoliittymän linkeillä on KGV:ssa (esim. suravage-linkit) tienumero, 
kiertoliittymän voi ns. "pikaosoitteistaa". Pikaosoitteistaminen tapahtuu klikkaamalla kiertoliittymän alkukohdaksi haluttua linkkiä. Tällöin koko kiertoliittymän linkit tulevat valituiksi. Uusi-toimenpide asettaa alkukohdaksi klikatun linkin.

Muussa tapauksessa kiertoliittymän alkukohta asetataan manuaalisesti kahdessa vaiheessa. 1. Valitaan alkupaikka tuplaklikkaamalla kiertoliittymän linkkiä tieosoitteen haluttuun alkupaikkaan. Valitulle linkille annetaan Uusi-toimenpiteellä tieosoite. 2. Kiertoliittymän loput linkit valitaan CTRL + klikkaamalla ja annetaan nille sama tieosoite.    

Tieosoiteprojektissa Uusi-toimenpiteellä jo tieosoiteistetun kiertoliittymän alkupaikka muutetaan palauttamalla kiertoliittymä ensin tieosoitteettomaksi ja osoitteistamalla se uudelleen. Valitse tieosoitteistettu kiertoliittymä ja käytä toimenpidettä "Palauta aihioksi tai tieosoitteettomaksi". Toimenpiteen jälkeen kiertoliittymän voi tieosoitteistaa uudelleen halutusta alkupaikasta aloittaen.

11.1.3 Ennallaan
--------------------------
Tieosan linkkien tieosoitteen voi säilyttää ennallaan esimerkiksi silloin, kun osalle tieosaa halutaan tehdä tieosoitemuutoksia ja osan säilyvän ennallaan. Tällöin tieosa käsitellään toimenpiteellä Ennallaan. Toimenpide tehdään varaamalla ensin projektitietojen formilla projektiin muokattava tieosa tai -osat. Seuraavaksi siirrytään toimenpidenäytölle Jatka toimenpiteisiin - painikkeella. Valittu tieosa tai sen tietyt linkit valitaan kartalta, jolloin ne muuttuvat vihreiksi, ja lomakkeelle ilmestyy alasvetovalikko. Valikosta valitaan toimenpide "Ennallaan" ja tallennetaan muutokset. Ennallaan-toimenpiteen voi tehdä vain tieosan alkuun; jos tieosoite on muuttunut jollain etäisyyslukemaltaan pienemmällä linkillä, se vaikuttaa myös jäljessä tulevien linkkien osoitteisiin, jolloin ennallaan-toimenpide ei ole näille enää mahdollinen.   

11.1.4 Siirto
--------------------------
Siirto-toimenpide tehdään tieosalle uusien m-arvojen laskemiseksi. Siirtoa käytetään, kun osa tieosan linkeistä käsitellään jollain muulla toimenpiteellä ja loppujen linkkien m-arvot täytyy laskea uudelleen. Esimerkkinä osalle tieosan linkeistä voidaan tehdä lakkautus, lisätä uusia linkkejä ja pitää osa linkeistä ennallaan. Siirto tehdään tieosoiteprojektiin varatulle tieosalle (varaaminen kuvattu kpl:ssa 10) siten, että tieosalle on ensin tehty muita toimenpiteitä, kuten lakkautus, uusi tai numerointi. Linkit, joille siirto tehdään, valitaan tuplaklikkaamalla ensimmäinen haluttu linkki ja lisäämällä valintaan CTRL + klikkaamalla linkkejä. Sitten valitaan toimenpidevalikosta siirto ja tallennetaan. Siirretyt linkit muuttuvat toimenpiteen tallennuksen jälkeen punaiseksi. Muutokset näkyvät projektin yhteenvetotaulukossa.   


11.1.5 Numeroinnnin muutos
--------------------------
Tieosoitteen numeroinnin muutoksella tarkoitetaan Viitteessä tienumeron ja/tai tieosanumeron muuttamista. 
Projektiin varataan tarvittava(t) tieosa(t), kuten kappaleessa 10 on kuvattu. Varaamisen jälkeen siirrytään toimenpidelomakkeelle Jatka toimenpiteisiin -painikkeella. Valitaan muokattava keltaisella näkyvä varattu tieosa klikkaamalla kartalta. Tieosa muuttuu vihreäksi. Viite poimii tällöin koko tieosan mukaan valintaan, vaikkei se näkyisi kokonaisuuudessaan karttanäkymässä ja käyttäjälle tulee tästä ilmoitus. Mikäli on tarpeen muuttaa vain tietyn linkin numerointia tieosalla, tehdään valinta tuplaklikkauksella halutun linkin päältä. Jos valitaan lisää yksittäisiä linkkejä, tehdään se CTRL + tuplaklikkaamalla. Toimenpide-lomakkeelle syötetään uusi numerointi (tienumero ja/tai tieosanumero) ja tallennetaan muutokset. Numeroitu osuus muuttuu tallennettaessa ruskeaksi. 
Koska numeroinnin muutos kohdistuu koko tieosaan, muita toimenpiteitä ei tallennuksen jälkeen tarvitse (eikä voi eikä saa) samalle tieosalle tehdä. 

11.1.6 Kääntö
--------------------------
### (!!HUOM. Kääntö on ollut pois käytöstä vuoden 2022 keväästä; Velho ei kykene vielä ottamaan vastaan kääntöjä.!!) ###

Tieosoitteen kasvusuunnan voi kääntää Viittessä joko esimerkiksi siirron tai numeroinnin yhteydessä. Kääntö tapahtuu tietyissä tapauksissa automaattisesti ja tietyissä tilanteissa käyttäjä tekee käännön manuaalisesti.  

_Automaattinen kääntö siirron yhteydessä:_

Kun siirretään tieosa (osittain tai kokonaan) toiselle tieosalle, jolla on eri tieosoitteen kasvusuunta, Viite päättelee siirron yhteydessä kasvusuunnan siirrettäville linkeille. 

Alla olevassa kuvasarjassa on tehty siirto ja kääntö osalle tieosaa. Projektiin on varattu tie 459 osa 1 ja tie 14 osa 1 (näkyvät kartalla keltaisella). Osa tien 14 linkeistä halutaan siirtää tielle 459 osalle 1 (lännen suuntaan), jolloin siirrettävät linkit valitaan kartalta. Lomakkeelta valitaan "Siirto" muutosilmoitus (1). Annetaan kohtaan TIE arvoksi kohdetien numero 459. Muut tiedot säilyvät tässä tapauksessa samana, mutta myös tieosaa, hallinnollista luokkaa ja jatkuvuuskoodia tulee tarvittaessa muuttaa. Tallennetaan muutokset.(2) Kohde muuttuu siirretyksi ja tieosoitteen kasvusuunta päivittyy vastaamaan tien 459 kasvusuuntaan (3). 

Tämän jälkeen siirtoon ja kääntöön voi valita lisää linkkejä tieltä 14 tai jättää loput ennalleen. Jälkimmäisessä tilanteessa loput projektiin valitut keltaiset aihiot tulee aina käsitellä 
jotta muutosilmoitukset voi hyväksyä tieosoiteverkolle. Tässä tapauksessa, mikäli muuta ei tehdä, tulee tie 459 osa 1 valita kartalta ja tehdä sille muutosilmoitus "Siirto" ja painaa Tallenna. Samoin tehdään tielle 14 osalle 1. Ilmoitusten yhteenvetotaulu avataan ja mikäli tiedot ovat valmiit, voi ne hyväksyä tieosoiteverkolle vihreästä painikkeesta.  

![Siirto ja kääntö](k48.JPG)

_Kuvasarjassa siirretään osa tiestä 14 tielle 459. Tieosoitteiden kasvusuunnat teillä ovat vastakkaiset, jolloin siirrossa tien 14 kasvusuunta kääntyy._



_Manuaalinen kääntö siirron ja numeroinnin yhteydessä:_

Manuaalista kääntöä varten Viitteessä on "Käännä kasvusuunta" -painike. Painike aktivoituu lomakkeelle kun käyttäjä on tehnyt varaamalleen aihiolle toimenpiteen ja tallentanut sen. Kun käsiteltyä aihiota (on tehty muutosilmoitus siirto tai numerointi) klikataan kartalla, lomakkeella näkyvät tehty ilmoitus ja sen tiedot sekä "Käännnä kasvusuunta" - painike. Kun sitä klikataan sekä tallennetaan, kasvusuunta kääntyy ja yhteenvetotauluun tulee tieto käännöstä oman sarakkeeseen "Kääntö" (rasti ruudussa). 

_Kaksiajorataisen osuuden kääntö_

Kun käännetään tieosan kaksiajoratainen osuus, se tehdään edellä kuvatulla tavalla siirron tai numeroinnin yhteydessä yksi ajorata kerrallaan. Kartalta valitaan haluttu ajorata ja lomakkeelta joko siirto tai numerointi. Määritetään uusi ajoratakoodi sekä muut tarvittavat tieosoitemuutostiedot lomakkeelle ja tallennetaan. Mikäli tieosoitteen kasvusuunta ei automaattisesti käänny (esim. kun käsitellään yhtä tieosaa), tehdään kääntö manuaalisesti "Käännä kasvusuunta" - painikkeella. Yhteenvetotaulussa "Kääntö"   
 sarake sekä muutosilmoituksen rivit päivittyvät. 
 
 
11.1.7 Etäisyyslukeman muutos
--------------------------
Tieosoiteprojektissa uudelle tieosoitteistettavalle tieosalle on mahdollista asettaa käyttäjän antama tieosan loppuetäisyyslukema. Ensin valitaan haluttu tieosa kartalta, jonka jälkeen lomakkeelle ilmestyy kenttä, johon loppuetäisyyden voi muuttaa. Muutettu arvo huomioidaan lomakkeella punaisella huutomerkillä. 

11.1.8 Suunnitelmalinkkien tieosoitteistaminen ja sen jakaminen saksi-työkalulla
--------------------------

(Poistettu, Viiteessä ei ole saksityökalua nykyisellään (2023-03))

11.1.9 Useiden muutosten tekeminen samalle tieosalle
--------------------------

11.1.10 ELY koodin, jatkuvuuden ja hallinnollisen luokan muutos
--------------------------
Viitteessä voi muokata ELY koodia, jatkuvuutta ja hallinnollista luokkaa. Näitä muutoksia voi tehdä esimerkiksi Ennallaan muutosilmoituksella, jolloin lomakkeelle tulee alasvetovalikot ELYlle, jatkuvuudelle ja hallinnolliselle luokalle. Uudet arvot annetaan valitulle aihiolle ja tallennetaan. Jatkuvuuskoodi näytetään valinnan viimeiseltä linkiltä ja muutokset kohdistuvat myös viimeiseen linkkiin. Hallinnollisen luokan ja ja ELY-koodin muutos kohdistuu kaikille valituille linkeille. Ennallaan-toimenpiteen lisäksi näitä arvoja voi muokata aina, kun ne ovat eri muutosilmoituksen yhteydessä lomakkeella muokattavissa. 


11.2 Muutosilmoitusten tarkastelu taulukkonäkymässä
--------------------------

Projektin muutosilmoitusten näkymässä on mahdollista tarkastella ilmoitusten yhteenvetotaulua. "Avaa projektin yhteenvetotaulukko" -painiketta (1) klikkaamalla avautuu taulukkonäkymä, joka kertoo projektissa olevien tieosoitteiden vanhan ja uuden tilanteen sekä tehdyn muutosilmoituksen. Taulukossa rivit on järjestetty suurimasta pienimpään tieosoitteen mukaan (tie, tieosa, alkuetäisyys, ajorata), jotta saman tien tieosuudet ovat taulukossa peräkkäin suurimmasta pienimpää. Yhteenvetotaulun AET- ja LET-arvot päivittyvät oikein vasta, kun kaikki tieosan aihiot on käsitelty. Muutosilmoitusten tekojärjestyksellä ei ole vaikutusta lopulliseen yhteenvetotauluun. 

Taulukon saa suurennettua ja pienennettyä sekä suljettua taulukon oikeasta yläkulmasta (2). Taulukon voi pitää auki muokatessa ja muutokset päivittyvät taulukkoon tallennettaessa. Viite-sovelluksen voi esimerkisi venyttää kahdelle näytölle, joista toisella voi tarkastella muutostaulukkoa ja käyttää karttanäkymää. 

![Avaus](k41.JPG)

_Taulukon avaus ja yhteenvetotaulukon näkymä._

11.3 Tarkastukset
--------------------------

Viite-sovellus tekee tieosoiteprojektissa automaattisia tarkastuksia jotka auttavat käyttäjää valmistelemaan tieosoiteprojektin teknisesti virheettömäksi. Tarkistukset ovat projektissa jatkuvasti päällä ja reagoivat projektin tilan muutoksiin aina "Päivitä etäisyyslukemat"-napin painalluksen jälkeen. Avatun projektin tarkistusilmoitukset ilmestyvät käyttäjälle projektissa oikealle tarkistusnäkymään "Jatka toimenpiteisiin"-napin painalluksen jälkeen. Tarkistusilmoituksia voi olla samanaikaisesti auki useita. Tieosoiteprojektin voi hyväksyä tieosoiteverkolle kun se läpäisee kaikki tarkistukset eli silloin kun oikean reunan formilla ei ole yhtään tarkistusilmoitusta.

![Tarkisilmoitusnäkymä](k49.JPG)

_Tarkistusilmoitukset näkyvät projektissa oikealla._

Tarkistusilmoitus koostuu seuraavista kentistä:

|Kenttä|Kuvaus|
|------|------|
|Linkids|Tarkistuksen kohteena oleva yksittäisen tielinkin ID. Vaihtoehtoisesti linkkien lukumäärä jos tarkistusilmoitus koskee useampaa linkkiä.|
|Virhe|Kuvaus tarkistuksen ongelmatilanteesta|
|Info|Mahdollisia lisäohjeita tarkistuksen virhetilanteen korjaamiseksi.|

![Tarkistuilmoitus](k50.JPG)

_Tielinkki 7304402 on tieosan viimeinen linkki mutta siltä puuttuu jatkuvuuskoodi "Tien loppu". (Huom. Kuva VVH:n ajalta, ja linkki-idn formaatti sen mukainen)_ 

Karttanäkymä kohdistuu tarkistusilmoituksen kohteena olevan tielinkin keskikohtaan painamalla "Korjaa"-painiketta. Painamalla Korjaa-nappia uudestaan kohdistus siirtyy seuraavaan tielinkkiin jos sama tarkistus kohdistuu useampaan linkkiin. Käyttäjä voi nyt valita tarkistusilmoituksen kohteena olevan tielinkin ja tehdä sille tarkistuksen korjaavan toimenpiteen. 

####Tieosoiteprojektissa tehtävät tarkastukset:####

Tieosoiteprojektiin kohdistuvat tarkistukset:

- Uusi tieosa ei saa olla varattuna jossakin toisessa tieosoiteprojektissa.
- Tieosoitteen kasvusuunta ei saa muuttuua kesken tien.
- Tieosoitteellinen tie ei saa haarautua muuta kuin ajoratakoodivaihdoksessa.
- Ajoratojen 1 ja 2 tulee kattaa samaa osoitealue.
- Tieosoitteelta ei saa puuttua tieosoiteväliä (katkoa m-arvoissa).

Jatkuvuuden tarkistukset:

- Tieosan sisällä jatkuvissa kohdissa (aukkopaikka alle 0,1 m), jatkuvuuskoodin tulee olla 5 (jatkuva)

- Tieosan sisällä epäjatkuvuuskohdissa (aukkopaikka yli 0,1 m) jatkuvuuskoodi tulee olla 4 (lievä epäjatkuvuus). 
Tieosan sisäisen epäjatkuvuuden pituudelle ei ole asetettu ylärajaa.

- Tieosan lopussa tulee olla jatkuvuuskoodi 2 (epäjatkuva), jos ennen tien seuraavaa tieosaa on epäjatkuvuuskohta. Seuraavaa tieosaa ei ole välttämättä valittu projektiin, joten tarkistus huomioi myös projektin ulkopuoliset tieosat.

- Tieosoitteen viimeisellä (suurin tieosanumero) tieosalla tulee olla jatkuuvuuskoodi 1 (tien loppu)

- Jos tieosoitteen viimeinen tieosa lakkautetaan kokonaan, niin tien edellisellä tieosalla tulee olla jatkuvuuskoodi 1 (tien loppu), tätä tieosaa ei ole välttämättä valittu projektiin, joten tarkistus ulottuu myös projektin ulkopuolisiin tieosiin.

- Jos tieosan seuraava tieosa on eri ELY-koodilla, niin jatkuvuuskoodin tulee olla tieosan lopussa 3 (ELY raja).

11.4 Muutosilmoitusten hyväksyminen tieosoiteverkolle
--------------------------

Muutosilmoitus hyväksytään tieosoiteverkolle avaamalle ensin Yhteenvetotaulukko klikkaamalla oikean alakulman sinistä Avaa yhteenvetotaulukko -painiketta. Kun projektilla ei ole enää korjaamattomia tarkistusilmoituksia, aktivoituu vihreä Laske etäisyyslukemat-painike, ja jos laskennankaan jälkeen ei löydy uusia virheitä, "Avaa yhteenvetotaulukko"-painike. Mikäli taulukon muutokset näyttävät oikeilta, voidaan edetä painamaan "Hyväksy tieosoitemuutokset"-nappia. Painikkeen painamisen jälkeen sovellus ilmoittaa
muutosilmoituksen tekemisestä "Projektia viedään tieosoiteverkolle." -viestillä.


Kun muutosilmoitus on lähetetty, muuttuu projektilistauksessa ko. projektin Tila-tieto statukselle "Odottaa tieverkolle päivittämistä". Viite-sovellus tarkistaa minuutin välein, onko muutos käsitelty loppuun asti. Kun tämä on tehty onnistuneesti, muuttuu Tila-tieto statukselle "Hyväksytty". Tällöin tieosoiteprojekti on hyväksytty onnistuneesti tieosoiteverkolle, ja se on valmis. Mikäli muutosilmoitus ei mennyt läpi, lukee tilana "Virhe Viiteessä", ja listalla on oranssi painike "Avaa uudelleen". Tarkemmin virheen tiedot pääsee tarkistamaan viemällä hiiren "Virhe Viiteessä" -tekstin päälle, jolloin infolaatikko virheestä tulee näkyviin. Virhe korjataan avaamalla projekti oranssista painikkeesta ja tekemällä tarvittavat muokkaukset sekä hyväksyttämällä ilmoitukset uudelleen tieosoiteverkolle.  

Projektia ei voi muokata, kun sen tila on joko "Odottaa tieverkolle päivittämistä", "Päivitetään tieverkolle" tai "Hyväksytty."

| Tieosoiteprojektin tila           | Selitys                                                                                                                                                                          |
|-----------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Keskeneräinen                     | Projekti on työn alla ja sitä ei ole vielä hyväksytty tieosoiteverkolle.                                                                                                         |
| Odottaa tieverkolle päivittämistä | Odottaa, että Viite ehtii ottaa projektin hyväksymiskäsittelyyn.                                                                                                                 |
| Päivitetään tieverkolle           | Projekti on Viiteessä käsittelyssä. Viite käsittelee projektin sisältämiä muutosilmoituksia. Muutokset eivät näy vielä Viiteessä/katselutilassa.                                 |
| Hyväksytty                        | Projekti on hyväksytty Viiteessä tieosoiteverkolle. Muutokset näkyvät myös Viite-sovelluksessa.                                                                                  |
| Virhe Viite-sovelluksessa         | Viite ei hyväksynyt projektia. Tarkempi virheilmoitus (mikäli sellainen on,) tulee näkyviin viemällä osoitin "Virhe Viiteessä"-tekstin päälle. Projektin voi avata uudelleen. |
![Tila-statuksia](k39.JPG)

_Tila-statuksia tieosoiteprojektit -listassa. (HUOM! vanha Tierekisterin aikainen kuva)_



