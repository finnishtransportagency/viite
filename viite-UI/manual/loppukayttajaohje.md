Viite-sovelluksen k&auml;ytt&ouml;ohje
======================================================
VIITE
-----------------------

VIITE on Liikenneviraston tieosoitej&auml;rjestelm&auml;n yll&auml;pito-sovellus. Viitteell&auml; hallitaan tieosoitej&auml;rjestelm&auml;n muutoksia ja se tarjoaa ajantasaisen kuvauksen tiest&ouml;st&auml; Digiroadin (VVH:n) ajantasaisella linkkigeometrialla.

Seuraavasta linkist&auml; p&auml;&auml;see Liikenneviraston extranet VIITE-sivulle (t&auml;ll&auml; hetkell&auml; testiextranet k&auml;yt&ouml;ss&auml;, varsinainen extranet-osoite p&auml;ivitet&auml;&auml;n my&ouml;hemmin), jossa kerrotaan Viitteen yleiskuvaus ja annetaan tiedotteita k&auml;ytt&auml;jille. Sivulla yll&auml;pidet&auml;&auml;n my&ouml;s dokumentaatiota Viitteest&auml;. 

https://testiextranet.vayla.fi/extranet/web/fi/viite?kategoria=7457637 (testi) 

__Huom! Suosittelemme Firefoxia tai Chromea, kun sovelluksella yll&auml;pidet&auml;&auml;n Digiroad-tietoja.__

__Huom! K&auml;ytt&ouml;ohjeen kuvia voi klikata isommaksi, jolloin tekstit erottuvat paremmin.__

1. Miten p&auml;&auml;st&auml; alkuun?
-----------------------

Viite-sovelluksen k&auml;ytt&ouml;&auml; varten tarvitaan Liikenneviraston tunnukset (A-, U-, LX-, K- tai L-alkuinen). Mik&auml;li sinulla ei ole tunnuksia, pyyd&auml; ne yhteyshenkil&ouml;lt&auml;si Liikennevirastosta.

Kaikilla Liikenneviraston tunnuksilla on p&auml;&auml;sy Viite-sovellukseen.

Viite-sovellukseen kirjaudutaan osoitteessa: <a href=https://extranet.vayla.fi/viite/ target="_blank">https://extranet.vayla.fi/viite/. </a>

![Kirjautuminen Viite-sovellukseen.](k1.JPG)

_Kirjautuminen Viite-sovellukseen._

Kirjautumisen j&auml;lkeen avautuu karttak&auml;ytt&ouml;liittym&auml;ss&auml; katselutila.

![N&auml;kym&auml; kirjautumisen j&auml;lkeen.](k2.JPG)

_Karttan&auml;kym&auml; kirjautumisen j&auml;lkeen._

Oikeudet on rajattu maantieteellisesti sek&auml; k&auml;ytt&auml;j&auml;n roolin mukaan.

- Ilman erikseen annettuja oikeuksia Liikenneviraston tunnuksilla p&auml;&auml;see katselemaan kaikkia tieosoitteita
- Sovelluksen k&auml;ytt&auml;j&auml;ll&auml; on oikeudet muokata h&auml;nelle m&auml;&auml;riteltyjen Elyjen maantieteellisten kuntarajojen sis&auml;puolella olevia tieosoitteita
- Joillekin k&auml;ytt&auml;jille on voitu antaa oikeudet koko Suomen alueelle
- Tieosoiteprojektit -painike ja Siirry muokkaustilaan -painike n&auml;kyv&auml;t vain k&auml;ytt&auml;jille, joilla on oikeudet muokata tieosoitteita

Jos kirjautumisen j&auml;lkeen ei avaudu karttak&auml;ytt&ouml;liittym&auml;n katselutilaa, ei kyseisell&auml; tunnuksella ole p&auml;&auml;sy&auml; Liikenneviraston extranettiin. T&auml;ll&ouml;in tulee ottaa yhteytt&auml; Liikennevirastossa tai Elyss&auml; omaan yhteyshenkil&ouml;&ouml;n.

1.1 Mist&auml; saada opastusta?
--------------------------

Viite-sovelluksen k&auml;yt&ouml;ss&auml; avustaa Janne Grekula janne.grekula@cgi.com.
####Ongelmatilanteet####

Sovelluksen toimiessa virheellisesti (esim. kaikki aineistot eiv&auml;t lataudu oikein) toimi seuraavasti:

- Lataa sivu uudelleen n&auml;pp&auml;imist&ouml;n F5-painikkeella.
- Tarkista, ett&auml; selaimestasi on k&auml;yt&ouml;ss&auml; ajan tasalla oleva versio ja selaimesi on Mozilla Firefox tai Chrome
- Jos edell&auml; olevat eiv&auml;t korjaa ongelmaa, ota yhteytt&auml; janne.grekula@cgi.com


2. Perustietoja Viite-sovelluksesta
--------------------------

2.2 Tiedon rakentuminen Viite-sovelluksessa
--------------------------

Viite-sovelluksessa tieosoiteverkko piirret&auml;&auml;n VVH:n tarjoaman Maanmittauslaitoksen keskilinja-aineiston p&auml;&auml;lle. Maanmittauslaitoksen keskilinja-aineisto muodostuu tielinkeist&auml;. Tielinkki on tien, kadun, kevyen liikenteen v&auml;yl&auml;n tai lauttayhteyden keskilinjageometrian pienin yksikk&ouml;. Tieosoiteverkko piirtyy geometrian p&auml;&auml;lle tieosoitesegmenttein&auml; _lineaarisen referoinnin_ avulla. 

Tielinkki on Viite-sovelluksen lineaarinen viitekehys, eli sen geometriaan sidotaan tieosoitesegmentit. Kukin tieosoitesegmentti tiet&auml;&auml; mille tielinkille se kuuluu (tielinkin ID) sek&auml; kohdan, josta se alkaa ja loppuu kyseisell&auml; tielinkill&auml;. Tieosoitesegmentit ovat siten tielinkin mittaisia tai niit&auml; lyhyempi&auml; tieosoitteen osuuksia. K&auml;ytt&ouml;liittym&auml;ss&auml; kuitenkin pienin valittavissa oleva osuus on tielinkin mittainen (kts. luvut 4.1 ja 7.1).

Kullakin tieosoitesegmentill&auml; on lis&auml;ksi tiettyj&auml; sille annettuja ominaisuustietoja, kuten tienumero, tieosanumero ja ajoratakoodi. Tieosoitesegmenttien ominaisuustiedoista on kerrottu tarkemmin kohdassa "Tieosoitteen ominaisuustiedot".

![Kohteita](k9.JPG)

_Tieosoitesegmenttej&auml; (1) ja muita tielinkkej&auml; (2) Viitteen karttaikunnassa._

Tieosoitesegmentit piirret&auml;&auml;n Viite-sovelluksessa kartalle erilaisin v&auml;rein (kts. kohta 4. Tieosoiteverkon katselu). Muut tielinkit, jotka eiv&auml;t kuulu tieosoiteverkkoon, piirret&auml;&auml;n kartalle harmaalla. N&auml;it&auml; ovat esimerkiksi tieosoitteettomat kuntien omistamat tiet, ajopolut, ajotiet jne.

Palautteet geometrian eli tielinkkien virheist&auml; voi laittaa Maanmittauslaitokselle, maasto@maanmittauslaitos.fi. Mukaan selvitys virheest&auml; ja sen sijainnista (esim. kuvakaappaus).

3. Karttan&auml;kym&auml;n muokkaus
--------------------------

![Karttan&auml;kym&auml;n muokkaus](k3.JPG)

_Karttan&auml;kym&auml;._

####Kartan liikutus####

Karttaa liikutetaan raahaamalla.

####Mittakaavataso####

Kartan mittakaavatasoa muutetaan joko hiiren rullalla, tuplaklikkaamalla, Ctrl+piirto (alue) tai mittakaavapainikkeista (1). Mittakaavapainikkeita k&auml;ytt&auml;m&auml;ll&auml; kartan keskitys s&auml;ilyy. Hiiren rullalla, tuplaklikkaamalla tai Shift+piirto (alue) kartan keskitys siirtyy kursorin kohtaan.  K&auml;yt&ouml;ss&auml; oleva mittakaavataso n&auml;kyy kartan oikeassa alakulmassa (2).

####Kohdistin####

Kohdistin (3) kertoo kartan keskipisteen. Kohdistimen koordinaatit n&auml;kyv&auml;t karttaikkunan oikeassa alakulmassa(4). Kun kartaa liikuttaa eli keskipiste muuttuu, p&auml;ivittyv&auml;t koordinaatit. Oikean alakulman valinnan (5) avulla kohdistimen saa my&ouml;s halutessaan piilotettua kartalta.

####Merkitse piste kartalla####

Merkitse-painike (6) merkitsee sinisen pisteen kartan keskipisteeseen. Merkki poistuu vain, kun merkit&auml;&auml;n uusi piste kartalta.

####Taustakartat####

Taustakartaksi voi valita vasemman alakulman painikkeista maastokartan, ortokuvat tai taustakarttasarjan. K&auml;yt&ouml;ss&auml; on my&ouml;s harmaas&auml;vykartta (t&auml;m&auml;n hetken versio ei kovin k&auml;ytt&ouml;kelpoinen).

####Hakukentt&auml;####

K&auml;ytt&ouml;liittym&auml;ss&auml; on hakukentt&auml; (8), jossa voi hakea koordinaateilla ja katuosoitteella tai tieosoitteella. Haku suoritetaan kirjoittamalla hakuehto hakukentt&auml;&auml;n ja klikkaamalla Hae. Hakutulos tulee listaan hakukent&auml;n alle. Hakutuloslistassa ylimp&auml;n&auml; on maantieteellisesti kartan nykyist&auml; keskipistett&auml; l&auml;himp&auml;n&auml; oleva kohde. Mik&auml;li hakutuloksia on vain yksi, keskittyy kartta automaattisesti haettuun kohteeseen. Jos hakutuloksia on useampi kuin yksi, t&auml;ytyy listalta valita tulos, jolloin kartta keskittyy siihen. Tyhjenn&auml; tulokset -painike tyhjent&auml;&auml; hakutuloslistan.

Tieosoitteella haku: Tieosoitteesta hakukentt&auml;&auml;n voi sy&ouml;tt&auml;&auml; koko osoitteen tai osan siit&auml;. Esim. 2 tai 2 1 150. (Varsinainen tieosoitehaku tieosoitteiden yll&auml;pidon tarpeisiin toteutetaan my&ouml;hemmin)

Koordinaateilla haku: Koordinaatit sy&ouml;tet&auml;&auml;n muodossa "pohjoinen (7 merkki&auml;), it&auml; (6 merkki&auml;)". Koordinaatit tulee olla ETRS89-TM35FIN -koordinaattij&auml;rjestelm&auml;ss&auml;. Esim. 6975061, 535628.

Katuosoitteella haku: Katuosoitteesta hakukentt&auml;&auml;n voi sy&ouml;tt&auml;&auml; koko ositteen tai sen osan. Esim. "Mannerheimintie" tai "Mannerheimintie 10, Helsinki".


4. Tieosoiteverkon katselu
--------------------------

Geometrialtaan yleistetty tieosoiteverkko tulee n&auml;kyviin kun zoomaa tasolle jossa mittakaavajanassa on lukema 5 km. T&auml;st&auml; tasosta ja sit&auml; l&auml;hemp&auml;&auml; piirret&auml;&auml;n kartalle valtatiet, kantatiet, seututiet, yhdystiet ja numeroidut kadut. Yleist&auml;m&auml;t&ouml;n tieverkko piirtyy mittakaavajanan lukemalla 2 km. 100 metri&auml; (100 metrin mittakaavajanoja on kaksi kappaletta) suuremmilla mittakaavatasoilla tulevat n&auml;kyviin kaikki tieverkon kohteet.

Tieosoiteverkko on v&auml;rikoodattu tienumeroiden mukaan. Vasemman yl&auml;kulman selitteess&auml; on kerrottu kunkin v&auml;rikoodin tienumerot. Lis&auml;ksi kartalle piirtyv&auml;t et&auml;isyyslukemasymbolit, eli ne kohdat, joissa vaihtuu tieosa tai ajoratakoodi. Tieverkon kasvusuunta n&auml;kyy kartalla pisaran mallisena nuolena.

![Mittakaavajanassa 2km](k4.JPG)

_Mittakaavajanassa 2 km._

![Mittakaavajanassa 100 m](k5.JPG)

_Mittakaavajanassa 100 m._

Tieosoitteelliset kadut erottuvat kartalla muista tieosoitesegmenteist&auml; siten, ett&auml; niiden ymp&auml;rill&auml; on musta v&auml;ritys.

![Tieosoitteellinen katu](k16.JPG)

_Tieosoitteellinen katu, merkattuna mustalla v&auml;rityksell&auml; tienumeron v&auml;rityksen lis&auml;ksi._

Kun hiiren vie tieosoiteverkon p&auml;&auml;lle, tulee kartalle n&auml;kyviin "infolaatikko", joka kertoo kyseisen tieosoitesegmentin tienumeron, tieosanumeron, ajoratakoodin, alkuet&auml;isyyden ja loppuet&auml;isyyden.

![Hover](k35.JPG)

_Infolaatikko, kun hiiri on viety tieosoitesegmentin p&auml;&auml;lle._

4.1 Kohteiden valinta
--------------------------
Kohteita voi valita klikkaamalla kartalta. Klikkaamalla kerran, sovellus valitsee kartalta ruudulla n&auml;kyv&auml;n osuuden kyseisest&auml; tieosasta, eli osuuden jolla on sama tienumero, tieosanumero ja ajoratakoodi. Valittu tieosa korostuu kartalla (1), ja sen tiedot tulevat n&auml;kyviin karttaikkunan oikeaan laitaan ominaisuustieton&auml;kym&auml;&auml;n (2).

![Tieosan valinta](k6.JPG)

_Tieosan valinta._

Tuplaklikkaus valitsee yhden tielinkin mittaisen osuuden tieosoitteesta. Valittu osuus korostuu kartalla (3), ja sen tiedot tulevat n&auml;kyviin karttaikkunan oikeaan laitaan ominaisuustieton&auml;kym&auml;&auml;n (4).

![Tieosoitesegmentin valinta](k7.JPG)

_Tielinkin mittaisen osuuden valinta._


##Tieosoitteen ominaisuustiedot##

Tieosoitteilla on seuraavat ominaisuustiedot:

|Ominaisuustieto|Kuvaus|Sovellus muodostaa|
|---------------|------|----------------|
|Muokattu viimeksi*|Muokkaajan k&auml;ytt&auml;j&auml;tunnus ja tiedon muokkaushetki.|X|
|Linkkien lukum&auml;&auml;r&auml;|Niiden tielinkkien lukum&auml;&auml;r&auml;, joihin valinta  kohdistuu.|X|
|Tienumero|Tieosoiteverkon mukainen tienumero. L&auml;ht&ouml;aineistona Tierekisterin tieosoitteet 2.1.2018.||
|Tieosanumero|Tieosoiteverkon mukainen tieosanumero. L&auml;ht&ouml;aineistona Tierekisterin tieosoitteet 2.1.2018.||
|Ajorata|Tieosoiteverkon mukainen ajoratakoodi. L&auml;ht&ouml;aineistona Tierekisterin tieosoitteet 2.1.2018.||
|Alkuet&auml;isyys**|Tieosoiteverkon et&auml;isyyslukemien avulla laskettu alkuet&auml;isyys. Et&auml;isyyslukeman kohdalla alkuet&auml;isyyden l&auml;ht&ouml;aineistona on Tierekisterin tieosoitteet 2.1.2018.|X|
|Loppuet&auml;isyys**|Tieosoiteverkon et&auml;isyyslukemien avulla laskettu loppuet&auml;isyys. Et&auml;isyyslukeman kohdalla loppuet&auml;isyyden l&auml;ht&ouml;aineistona on Tierekisterin tieosoitteet 2.1.2018.|X|
|ELY|Liikenneviraston ELY-numero.|X|
|Tietyyppi|Muodostetaan Maanmittauslaitoksen hallinnollinen luokka -tiedoista, kts. taulukko alempana. Jos valitulla tieosalla on useita tietyyppej&auml;, ne kerrotaan ominaisuustietotaulussa pilkulla erotettuna.|X|
|Jatkuvuus|Tieosoiteverkon mukainen jatkuvuus-tieto. L&auml;ht&ouml;aineistona Tierekisterin tieosoitteet 2.1.2018.|X|

*)Muokattu viimeksi -tiedoissa vvh_modified tarkoittaa, ett&auml; muutos on tullut Maanmittauslaitokselta joko geometriaan tai geometrian ominaisuustietoihin. Muokattu viimeksi -p&auml;iv&auml;t ovat kaikki v&auml;hint&auml;&auml;n 29.10.2015, koska tuolloin on tehty Maanmittauslaitoksen geometrioista alkulataus VVH:n tietokantaan.

**)Tieosoiteverkon et&auml;isyyslukemat (tieosan alku- ja loppupisteet sek&auml; ajoratakoodin vaihtuminen) m&auml;&auml;rittelev&auml;t mitatut alku- ja loppuet&auml;isyydet. Et&auml;isyyslukemien v&auml;lill&auml; alku- ja loppuet&auml;isyydet lasketaan tieosoitesegmenttikohtaisesti Viite-sovelluksessa.

__Tietyypin muodostaminen Viite-sovelluksessa__

J&auml;rjestelm&auml; muodostaa tietyyppi-tiedon automaattisesti Maanmittauslaitoksen aineiston pohjalta seuraavalla tavalla:

|Tietyyppi|Muodostamistapa|
|---------|---------------|
|1 Maantie|MML:n hallinnollinen luokka arvolla 1 = Valtio|
|2 Lauttav&auml;yl&auml; maantiell&auml;|MML:n hallinnollinen luokka arvolla 1 = Valtio ja MML:n kohdeluokka arvolla lautta/lossi|
|3 Kunnan katuosuus|MML:n hallinnollinen luokka arvolla 2 = Kunta|
|5 Yksityistie|MML:n hallinnollinen luokka arvolla 3 = Yksityinen|
|9 Ei tiedossa|MML:lla ei tiedossa hallinnollista luokkaa|

Palautteet hallinnollisen luokan virheist&auml; voi toimittaa Maanmittauslaitokselle osoitteeseen maasto@maanmittauslaitos.fi. Mukaan selvitys virheest&auml; ja sen sijainnista (kuvakaappaus tms.).

##Kohdistaminen tieosoitteeseen tielinkin ID:n avulla##

Kun kohdetta klikkaa kartalla, tulee selaimen osoiteriville n&auml;kyviin valitun kohteen tielinkin ID. Osoiterivill&auml; olevan URL:n avulla voi my&ouml;s kohdistaa k&auml;ytt&ouml;liittym&auml;ss&auml; ko. tielinkkiin. URL:n voi esimerkiksi l&auml;hett&auml;&auml; toiselle henkil&ouml;lle s&auml;hk&ouml;postilla, jolloin h&auml;n p&auml;&auml;see samaan paikkaan k&auml;ytt&ouml;liittym&auml;ss&auml; helposti.

Esimerkiksi: https://extranet.vayla.fi/viite/#linkProperty/799497 n&auml;kyy kuvassa osoiterivill&auml; (5). 799497 on tielinkin ID.

![Kohdistaminen tielinkin ID:ll&auml;](k8.JPG)

_Kohdistaminen tielinkin ID:ll&auml;._


5. Automatiikka Viite-sovelluksessa
--------------------------
Viite-sovelluksessa on muutamia automatiikan tekemi&auml; yleistyksi&auml; tai korjauksia. Automatiikka ei muuta mit&auml;&auml;n sellaisia tietoja, jotka muuttaisivat varsinaisesti tieosoitteita. Automatiikan tekem&auml;t muutokset liittyv&auml;t siihen, ett&auml; tieosoiteverkkoa yll&auml;pidet&auml;&auml;n keskilinjageometrian p&auml;&auml;ll&auml;, ja tuon keskilinjageometrian yll&auml;pidosta vastaa Maanmittauslaitos. Tietyt automaattiset toimenpiteet helpottavat tieosoiteverkon yll&auml;pit&auml;j&auml;&auml; varsinaisessa tieosoiteverkon hallinnassa.

__Huom! Automatiikka ei koskaan muuta tieosan mitattua pituutta. Arvot tieosien ja ajoratojen vaihtumiskohdissa pysyv&auml;t aina ennallaan automatiikan tekemien korjausten yhteydess&auml;.__

##5.1 Tieosoitesegmenttien yhdistely tielinkin mittaisiksi osuuksiksi##

Kun k&auml;ytt&auml;j&auml; valitsee kartalla kohteita tuplaklikkaamalla (luku 4.1), on pienin valittava yksikk&ouml; tielinkin mittainen osuus tieosoiteverkosta, koska tielinkki on pienin mahdollinen yksikk&ouml; Maanmittauslaitoksen yll&auml;pit&auml;m&auml;ll&auml; linkkiverkolla.

T&auml;t&auml; varten j&auml;rjestelm&auml; tekee automaattista yhdistely&auml;:

- Ne kohteet, joiden tielinkin ID, tie, tieosa, ajorata ja alkup&auml;iv&auml;m&auml;&auml;r&auml; ovat samoja (=tieosoitehistoria yhtenev&auml;), on yhdistetty tietokannassa yhdeksi tielinkin mittaiseksi tieosoitesegmentiksi
- Ne kohteet, joiden tielinkin ID, tie, tieosa, ajorata ovat samoja, mutta alkup&auml;iv&auml;m&auml;&auml;r&auml; ei ole sama (=tieosoitehistoria erilainen) ovat tietokannassa edelleen erillisi&auml; kohteita, mutta k&auml;ytt&ouml;liittym&auml;ss&auml; ne ovat valittavissa vain tielinkin mittaisena osuutena. T&auml;ll&auml; varmistetaan, ett&auml; k&auml;ytt&ouml;liittym&auml; toimii k&auml;ytt&auml;j&auml;n kannalta loogisesti, eli aina on valittavissa tielinkin mittainen osuus, mutta tieosoitehistoria s&auml;ilytet&auml;&auml;n tietokantatasolla.

##5.2 Tieosoitesegmenttien automaattinen korjaus jatkuvasti p&auml;ivittyv&auml;ll&auml; linkkigeometrialla##

Viite-sovellus p&auml;ivitt&auml;&auml; automaattisesti tieosoitesegmentit takaisin ajantasaiselle keskilinjalle, kun MML on tehnyt pieni&auml; tarkennuksia keskilinjageometriaan. T&auml;ss&auml; luvussa on kuvattu tapaukset, joissa Viite-sovellus osaa tehd&auml; korjaukset automaattisesti. Ne tapaukset, joissa korjaus ei tapahdu automaattisesti, segmentit irtoavat geometriasta ja ne on korjattava manuaalisesti operaattorin toimesta.

Automatiikka tekee korjaukset, kun...

1. __Tielinkki pitenee tai lyhenee alle metrin:__ Viite-sovellus lyhent&auml;&auml;/pident&auml;&auml; tieosoitesegmentti&auml; automaattisesti muutoksen verran.
1. __Maanmittauslaitos yhdistelee tielinkkej&auml;, esimerkiksi poistamalla tonttiliittymi&auml; maanteiden varsilta:__ Tieosoitesegmentit siirret&auml;&auml;n uudelle geometrialle automaattisesti V&auml;yl&auml;verkon hallinnan (VVH) tarjoaman tielinkkien muutosrajapinnan avulla.


6. Tieosoiteprojektin tekeminen
--------------------------

Uuden tieosoiteprojektin tekeminen aloitetaan klikkaamalla painiketta Tieosoiteprojektit (1) ja avautuvasta ikkunasta painiketta Uusi tieosoiteprojekti (2).

![Uusi tieosoiteprojekti](k17.JPG)

_Tieosoiteprojektit -painike ja Uusi tieosoiteprojekti -painike._

N&auml;yt&ouml;n oikeaan laitaan avautuu lomake tieosoiteprojektin perustietojen t&auml;ydent&auml;miseen. Jos k&auml;ytt&auml;j&auml; ei ole muokkaustilassa, sovellus siirtyy t&auml;ss&auml; vaiheessa automaattisesti muokkaustilaan.

![Uusi tieosoiteprojekti](k18.JPG)

_Tieosoiteprojektin perustietojen lomake._

Pakollisia tietoja ovat nimi ja projektin muutosten voimaantulop&auml;iv&auml;m&auml;&auml;r&auml;, jotka on merkattu lomakkeelle oranssilla (3). Projektiin ei ole pakko t&auml;ydent&auml;&auml; yht&auml;&auml;n tieosaa. Lis&auml;tiedot -kentt&auml;&auml;n k&auml;ytt&auml;j&auml; voi halutessaan tehd&auml; muistiinpanoja tieosoiteprojektista. Tiedot tallentuvat painamalla Jatka toimenpiteisiin -painiketta (4). My&ouml;s Poistu-painikkeesta Viite varmistaa haluaako k&auml;ytt&auml;j&auml; tallentaa projektiin tehdyt muutokset.

![Uusi tieosoiteprojekti](k19.JPG)

_Tieosoiteprojektin perustietojen t&auml;ytt&auml;minen._

Projektin tieosat lis&auml;t&auml;&auml;n t&auml;ydent&auml;m&auml;ll&auml; niiden tiedot kenttiin tie, aosa, losa ja painamalla painiketta Varaa (5). __Kaikki kent&auml;t tulee t&auml;yt&auml;&auml; aina, kun haluaa varata tieosan!__ 

Varaa -painikkeen painamisen j&auml;lkeen tieosan tiedot tulevat n&auml;kyviin lomakkeelle.

![Uusi tieosoiteprojekti](k22.JPG)

_Tieosan tiedot lomakkeella Lis&auml;&auml; -painikkeen painamisen j&auml;lkeen._

Tieosoiteprojekti tallentuu automaattisesti painikkeesta Jatka toimenpiteisiin, jolloin tiedot tallentuvat tietokantaan ja sovellus siirtyy toimenpiden&auml;yt&ouml;lle. Varaamisen yhteydess&auml; Viite zoomaa kartan varatun tieosan alkuun. Poistu-painikkeesta projekti suljetaan ja k&auml;ytt&auml;j&auml;lt&auml; varmistetaan, halutaanko tallentamattomat muutokset tallentaa. Projektiin p&auml;&auml;see palaamaan Tieosoiteprojektit -listan kautta. 

K&auml;ytt&auml;j&auml; voi poistaa varattuja tieosia klikkaamalla ruksia tieosan oikealla puolella, Projektiin valitut tieosat-listalta. Mik&auml;li tieosille ei ole tehty muutoksia eli ne on pelkast&auml;&auml;n varattu projektiin, varaus poistetaan projektista. Jos tieosille on tehty muutoksia, Viite kysyy k&auml;ytt&auml;j&auml;lt&auml; varmistuksen poistosta. T&auml;ll&ouml;in kaikki muutokset menetet&auml;&auml;n.



![Uusi tieosoiteprojekti](k20.JPG)

_Kun tieosa on varattu projektiin Viite zoomaa kartan siten ett&auml; tieosa n&auml;kyy kartalla kokonaisuudessaan._  

Varauksen yhteydess&auml; j&auml;rjestelm&auml; tekee varattaville tieosille tarkistukset:

- Onko varattava tieosa olemassa projektin voimaantulop&auml;iv&auml;n&auml;
- Onko varattava tieosa vapaana, eik&auml; varattuna mihink&auml;&auml;n toiseen projektiin

Virheellisist&auml; varausyrityksist&auml; j&auml;rjestelm&auml; antaa asianmukaisen virheilmoituksen.  __K&auml;ytt&auml;j&auml;n tulee huomioida, ett&auml; varauksen yhteydess&auml; kaikki kent&auml;t (TIE, AOSA, LOSA) tulee t&auml;ytt&auml;&auml;, tai k&auml;ytt&auml;j&auml; saa virheilmoituksen!__


6.1 Olemassa olevan tieosoiteprojektin avaaminen Tieosoiteprojektit -listalta
--------------------------

Tallennetun tieosoiteprojektin saa auki Tieosoiteprojektit -listalta painamalla Avaa -painiketta. Avaamisen yhteydess&auml; sovellus zoomaa kartan paikkaan, jossa k&auml;ytt&auml;j&auml; on viimeimm&auml;ksi tallentanut toimenpiteen. Mik&auml;li toimenpiteit&auml; ei ole tehty, karttan&auml;kym&auml; rajautuu siten, ett&auml; kaikki varatut aihiot n&auml;kyv&auml;t karttan&auml;kym&auml;ss&auml;

Tieosoiteprojektit -listalla n&auml;kyv&auml;t kaikkien k&auml;ytt&auml;jien projektit. Projektit on j&auml;rjestetty ELY-koodien mukaiseen j&auml;rjestykseen ja niiden sis&auml;ll&auml; projektin nimen ja k&auml;ytt&auml;j&auml;tunnuksen mukaiseen j&auml;rjestykseen. Projektin tekij&auml;n k&auml;ytt&auml;j&auml;tunnus n&auml;kyy my&ouml;s projektilistauksessa.

![Uusi tieosoiteprojekti](k26.JPG)

_Tieosoiteprojektit -listaus._

7. Muutosilmoitusten tekeminen tieosoiteprojektissa
--------------------------


Tieosoiteprojektissa on mahdollista tehd&auml; seuraavia muutosilmoituksia:


- lakkautus (tieosoitteen lakkautus) 
- uusi (lis&auml;t&auml;&auml;n uusi tieosoite osoitteettomalle linkille) 
- ennallaan (osa tieosoitteesta s&auml;ilyy ennallaan, kun osaa siit&auml; muutetaan)
- siirto (tieosan alkuet&auml;isyys- ja loppuet&auml;isyysarvot p&auml;ivitet&auml;&auml;n, kun muulle osalle tieosaa tehd&auml;&auml;n muutos)
- numeroinnin muutos (kokonaisen tieosan tienumeron ja/tai tieosanumeron voi muuttaa manuaalisesti) 
- k&auml;&auml;nt&ouml; (tieosoitteen kasvusuunnan k&auml;&auml;nt&ouml;)
- et&auml;isyyslukeman muutos (et&auml;isyyslukeman loppuarvon voi sy&ouml;tt&auml;&auml; tieosalle manuaalisesti)
- ELY koodin, jatkuvuuden ja tietyypin muutos

T&auml;ss&auml; sek&auml; seuraavissa kappaleissa kuvataan muutosilmoitusten teko Viitteess&auml;.


Tieosoiteprojektissa muutostoimenpiteit&auml; p&auml;&auml;see tekem&auml;&auml;n klikkaamalla Jatka toimenpiteisiin-painiketta tieosoitemuutosprojektin perustietojen lomakkeella. T&auml;m&auml;n j&auml;lkeen sovellus muuttaa varatut tieosat muokattaviksi kohteiksi ja ne n&auml;kyv&auml;t avautuvassa karttan&auml;kym&auml;ss&auml; keltaisella korostettuna (1). Mik&auml;li toimenpiteen&auml; lis&auml;t&auml;&auml;n uusi tieosoite, eli tieosia ei ole varattu projektiin, kartalta ei valikoidu mit&auml;&auml;n ennen k&auml;ytt&auml;j&auml;n tekem&auml;&auml; valintaa. T&auml;m&auml; tarkennetaan tulevissa toimenpiteit&auml; kuvaavissa kappaleissa.

Projektitilassa, vain projektiin varattuja tieosia, tuntemattomia tielinkkej&auml;, muun tieverkon linkkej&auml; tai suunnitelmalinkkej&auml; (suravage-linkkej&auml;), voi valita projektissa klikkaamalla kartalta. Suunnitelmalinkit saa pois piirrosta ja piirtoon sivun alapalkissa olevasta valinnasta. Ne ovat oletuksena piirrossa. Tieverkon tieosoitetietoja voi katsella kartalla viem&auml;ll&auml; hiiren tieosoitelinkin p&auml;&auml;lle. T&auml;ll&ouml;in tielinkin infolaatikko tulee n&auml;kyviin. 
Projektin nimen vieress&auml; on sininen kyn&auml;ikoni (2), josta p&auml;&auml;see projektin perustietojen lomakkeelle muokkaamaan projektin tietoja. Lis&auml;ksi oikeassa yl&auml;kulamssa on Poistu projektista -linkki (3), josta p&auml;&auml;see Viitteen alkutilaan. Viite tarkistaa haluaako k&auml;ytt&auml;j&auml; tallentaa muutokset.

![Aihio](k36.JPG)

_Projektissa muokattavissa olevat varatut tieosat n&auml;kyv&auml;t kartalla keltaisella v&auml;rill&auml; ja suuntanuolet ovat tien alkuper&auml;isen v&auml;rin mukaiset (siniset). Projektin nimi on "Esimerkki-projekti", joka n&auml;kyy oikeassa yl&auml;kulmassa._

Projektin muutosilmoitukset tallentuvat projektin yhteenvetotauluun, jonka voi avata n&auml;kyviin toimenpiden&auml;kym&auml;n alaoikeasta laidasta sinisest&auml; painikkeesta (4). Yhteenvetotaulun toiminta on kuvattu tarkemmin kappaleessa 7.2. Lis&auml;ksi kaikkien projektien muutostiedot voidaan l&auml;hett&auml;&auml; Tierekisteriin klikkaamalla vihre&auml;&auml; Tee tieosoitemuutosilmoitus -painiketta (5) sivun alaoikealla. Muutosilmoituksen l&auml;hett&auml;minen on kuvattu kappaleessa 7.3. 

Kun keltaista, muokattavaa kohdetta kertaklikkaa kartalla, muuttuu valittu osuus vihre&auml;ksi ja oikeaan laitaan tulee alasvetovalikko, josta voi valita kohteelle teht&auml;v&auml;n muutosilmoituksen (esim. lakkautus). Kerran klikkaamalla valitaan kartalta homogeeninen jakso (= sama tienumero, tieosanumero, ajoratakoodi, tietyyppi ja jatkuvuus). Tuplaklikkaus tai CTRL+klikkaus valitsee yhden tieosoitesegmentin verran (tielinkin mittainen osuus). Kun halutaan valita vain osa tieosan linkeist&auml;, tupla- tai CTRL+klikataan ensimm&auml;ist&auml; linkki&auml; ja seuraavat linkit lis&auml;t&auml;&auml;n valintaan CTRL+klikkauksella samalta tieosalta. Samalla tavalla voi my&ouml;s poistaa yksitt&auml;isi&auml; linkkej&auml; valinnasta. 

![Valittu kohde](k37.JPG)

_Kun keltaista, muokattavissa olevaa kohdetta klikataan, muuttuu tieosa vihre&auml;ksi ja oikeaan laitaan tulee n&auml;kyviin valikko, jossa on tieosoitemuutosprojektin mahdolliset muutosilmoitukset._

Muutokset tallennetaan oikean alakulman Tallenna-painikkeesta. Ennen tallennusta, voi muutokset perua Peruuta-painikkeesta, jolloin Viite palaa edelt&auml;v&auml;&auml;n vaiheeseen.

Jos k&auml;ytt&auml;j&auml; on jo tehnyt projektissa muutoksia tieosoitteille, ne tulevat n&auml;kyviin lomakkeelle klikatessa kyseist&auml; tielinkki&auml;, jolle muutokset on tehty. Esimerkiksi, mik&auml;li tieosalle on toteutettu Lakkautus, tieosa valittaessa sen tiedot lakkautuksesta ilmestyv&auml;t lomakkeelle ja tieosalle on mahdollista tehd&auml; toinen toimenpide. Mahdolliset uudet toimenpidevaihtoehdot kunkin toimenpiteen tallentamisen j&auml;lkeen, on kuvattu seuraavissa kappaleissa, joissa kerrotaan kunkin toimenpiteen tekemisest&auml; tarkemmin. 

Selite projektitilassa on erilainen kuin katselutilassa. Projektitilan selite kuvaa linkkiverkkoon tehdyt toimenpiteet kun taas katselutilan selite kuvaa tieluokitusta.



7.1 Muutosilmoitusten kuvaukset
--------------------------

7.1.1 Lakkautus
--------------------------
Kun halutaan lakkauttaa joko tieosia, tieosa tai osa tieosasta, tulee tarvittavat tieosat varata projektiin. Varaaminen tehd&auml;&auml;n, kuten kpl 10. esitetty, eli sy&ouml;tet&auml;&auml;n projektitietojen lomakkeelle haluttu tienumero ja tieosa sek&auml; painetaan Lis&auml;&auml;-painiketta.
T&auml;m&auml;n j&auml;lkeen Jatka toimenpiteisiin-painiketta, jolla siirryt&auml;&auml;n toimenpidelomakkeelle tekem&auml;&auml;n tieosoitemuutosta. Toimenpidelomakkeella valitaan kartalta projektiin varattu tieosa, -osat tai tarvittavat linkit valitusta tieosasta. Ne muuttuvat valittuna vihre&auml;ksi. (Shif+tuplaklikkaus-painalluksella voi lis&auml;t&auml; yksitt&auml;isi&auml; linkkej&auml; valintaan tai poistaa yksitt&auml;isi&auml; linkkej&auml; valinasta.) Toimenpide-lomakkeelle tulee tiedot valituista linkeist&auml; sek&auml; pudotusvalikko, josta valitaan Lakkautus. T&auml;m&auml;n j&auml;lkeen tallennetaan muutos projektiin. Lakkautettu linkki tulee n&auml;kyviin mustalla ja sen tiedot p&auml;ivittyv&auml;t yhteenvetotaulukkoon, jonka voi avata sinisest&auml; Avaa projektin yhteenvetotaulukko-painikkeesta. Yhteenvetotaulukon toiminta on kuvattu kappaleessa 7.2. Mik&auml;li on lakkautettu vain osa tieosan linkeist&auml;, tulee tieosan muut kuin lakkautetut linkit my&ouml;s k&auml;sitell&auml; joko ennallaan- tai siirto-toimenpiteill&auml;, riippuen tilanteesta. Kun tarvittavat muutoset projektissa on tehty, muutostiedot voi l&auml;hett&auml;&auml; Tierekisteriin painamalla Tee tieosoitemuutosilmoitus-painiketta. 

7.1.2 Uusi
--------------------------

Toimenpiteell&auml; m&auml;&auml;ritet&auml;&auml;n uusi tieosoite tieosoitteettomille linkeille. Tieosoitteettomia muun tieverkon linkkej&auml;, jotka piirtyv&auml;t kartalle harmaina tai tuntemattomia mustia linkkej&auml;, joissa on kysymysmerkkisymboli tai suravage-linkkej&auml;, voi valita kerta- tai tuplaklikkauksella, kuten muitakin tielinkkej&auml;. Tuplaklikkaus valitsee yhden tielinkin ja CTRL+klikkauksella voi lis&auml;t&auml; tai poistaa valintaan linkkej&auml; yksi kerrallaan. Kertaklikkaus valitsee homogeenisen jakson, jossa k&auml;ytet&auml;&auml;n VVH:n tienumeroa ja tieosanumeroa. Tienumeron tai tieosanumeron puuttuessa valinnassa k&auml;ytet&auml;&auml;n tienime&auml;.

Valitut tielinkit n&auml;kyv&auml;t kartalla vihre&auml;ll&auml; korostettuna. Kun valitaan Toimenpiteet-alasvetovalikosta 'Uusi' (1) lomakkeelle avautuvat kent&auml;t uuden tieosoitteen tiedoille (2). Jos valitulla tieosuudella on jo olemassa VVH:ssa tienumero ja tieosanumero, ne esit&auml;yttyv&auml;t kenttiin automattisesti.

![Uusi tieosoite](k43.JPG)

_Kun toimenpidevalikosta valitaan 'Uusi', oikeaan laitaan ilmestyy n&auml;kyviin kent&auml;t uuden tieosoitteen sy&ouml;tt&auml;mist&auml; varten._ 

Tietyyppi&auml; voi muokata pudotusvalikosta (3). Jatkuu-arvo m&auml;&auml;r&auml;ytyy ensimm&auml;isell&auml; tallennuskerralla automaattisesti jatkuvaksi (5 Jatkuva). Muutokset tallennetaan Tallenna-painikkeella (4). Ennen tallennusta, muutokset voi perua Peruuta-painikkeesta. 

Huom: Mik&auml;li jatkuvuutta t&auml;ytyy muokata, esimerkiksi jos kyseess&auml; on tien loppu, se tulee tehd&auml; seuraavasti. K&auml;ytt&auml;j&auml;n tulee klikata viimeinen "Uusi" toimenpiteell&auml; k&auml;sitelty linkki tieosan lopusta aktiiviseksi, jossa tien loppu sijaitsee. Lomakkeelle tulee tiedot linkin osoitteesta ja sille tehdyst&auml; toimenpiteest&auml;. Nyt linkin jatkuvuuskoodin muokkaaminen on mahdollista ja oikea koodi, esimerkiksi 1 Tien loppu, valitaan pudotusvalikosta ja tallennetaan. P&auml;ivitetty tieto n&auml;kyy my&ouml;s yhteenvetotaulukossa tallennuksen j&auml;lkeen.   


K&auml;ytt&ouml;liittym&auml; varoittaa virheilmoituksella jos uusi tieosoite on jo olemassa projektin alkup&auml;iv&auml;n&auml; tai se on varattuna toisessa tieosoiteprojektissa.

![Tieosoite on jo olemassa](k44.JPG)

_Tieosoite on jo olemassa projektin alkup&auml;iv&auml;n&auml;._

![Kasvusuunnan vaihto](k47.JPG)

_Valittuna olevan uuden tieosoitteen vaikutussuntaa vaihtuu lomakkeen 'K&auml;&auml;nn&auml; vaikutussuunta'-nappulasta._


Uuden tieosoitteen linkit piirtyv&auml;t kartalle pinkill&auml; (2). Tieosan alku- ja loppupisteisiin sijoitetaan automaattisesti et&auml;isyyslukema-symbolit. Viite laskee uudelle tieosuudelle automaattisesti my&ouml;s linkkien m-arvot k&auml;ytt&auml;en VVH:n tietoja. Uudelle tieosoitteelle m&auml;&auml;rittyy aluksi satunnainen kasvusuunta, joka n&auml;kyy kartalla pinkkien nuolien suunnasta.

![Uusi tieosoite pinkilla](k46.JPG)

_Uuden tieosoitteen linkit piirtyv&auml;t kartalle pinkill&auml;. Tieosan voi valita klikkaamalla, jolloin se korostuu vihre&auml;ll&auml;._


Tallennettuun tieosoitteeseen voi jatkaa uusien linkkien lis&auml;&auml;mist&auml; vaiheittain. Ensin valitaan tallennetun tieosan jatkeeksi seuraava linkki ja sitten valitaan lomakkeelta toimenpide "uusi" ja annetaan linkeille sama tieosoite (TIE= tienumero, OSA=tieosanumero, AJR=ajoratakoodi). ELY- ja Jatkuu-arvot Viite t&auml;ytt&auml;&auml; automaattisesti. ELY-koodi m&auml;&auml;r&auml;ytyy tielinkin kuntakoodin perustella VVH:sta. Tallennetaan lis&auml;ykset. Projektin voi my&ouml;s tallentaa, sulkea ja jatkaa lis&auml;yst&auml; samaan tieosoitteeseen my&ouml;hemmin. Kasvusuunta lis&auml;tylle osuudelle m&auml;&auml;r&auml;ytyy aiemmin osoitteistettujen linkkien mukaan ja sit&auml; voi edelleen k&auml;&auml;nt&auml;&auml; K&auml;&auml;nn&auml; kasvusuunta-painikkeella. M-arvot p&auml;ivittyv&auml;t koko tieosalle, jolle on annettu sama tieosoite.

Tieosoitteen voi antaa Viitteess&auml; my&ouml;s ns. Suravage-linkeille (SuRavaGe = Suunniteltu rakentamisvaiheen geometria). Suravage-tiet n&auml;kyv&auml;t Viitteess&auml; vaaleanpunaisella v&auml;rill&auml; ja niiss&auml; n&auml;kyy my&ouml;s tieosoitteen kasvusuuntanuolet. 

__Uuden kiertoliittym&auml;n alkupaikan muuttaminen__

Jos Uusi-toimenpiteell&auml; tieosoitteistetulla kiertoliittym&auml;n; linkeill&auml; on VVH:ssa (esim. suravage-linkit) tienumero, kiertoliittym&auml;n voi ns. "pikaosoitteistaa". Pikaosoitteistaminen tapahtuu kertaamalla kiertoliittym&auml;n alkukohdaksi haluttua linkki&auml;. T&auml;ll&ouml;in koko kiertoliittym&auml;n linkit tulevat valituiksi. Uusi toimenpide asettaa alkukohdaksi klikatun linkin.

Muussa tapauksessa kiertoliittym&auml;n alkukohta asetataan manuaalisesti kahdessa vaiheessa. 1. Valitaan alkupaikka tuplaklikkaamalla kiertoliittym&auml;n linkki&auml; tieosoitteen haluttuun alkupaikkaan. Valitulle linkille annetaan Uusi-toimenpiteell&auml; tieosoite. 2. Kiertoliittym&auml;n loput linkit valitaan CTRL + klikkaamalla ja annetaan nille sama tieosoite.    

Tieosoiteprojektissa Uusi-toimenpiteell&auml; jo tieosoiteistetun kiertoliittym&auml;n alkupaikka muutetaan palauttamalla kiertoliittym&auml; ensin tieosoitteettomaksi ja osoitteistamalla se uudelleen. Valitse tieosoitteistettu kiertoliittym&auml; ja k&auml;yt&auml; toimenpidett&auml; "Palauta aihioksi tai tieosoitteettomaksi". Toimenpiteen j&auml;lkeen kiertoliittym&auml;n voi tieosoitteistaa uudelleen halutusta alkupaikasta aloittaen.

7.1.3 Ennallaan
--------------------------
Tieosan linkkien tieosoitteen voi s&auml;ilytt&auml;&auml; ennallaan esimerkiksi silloin, kun osalle tieosaa halutaan tehd&auml; tieosoitemuutoksia ja osan s&auml;ilyv&auml;n ennallaan. T&auml;ll&ouml;in tieosa k&auml;sitell&auml;&auml;n toimenpiteell&auml; Ennallaan. Toimenpide tehd&auml;&auml;n varaamalla ensin projektitietojen formilla projektiin muokattava tieosa tai -osat. Seuraavaksi siirryt&auml;&auml;n toimenpiden&auml;yt&ouml;lle Jatka toimenpiteisiin - painikkeella. Valittu tieosa tai sen tietyt linkit valitaan kartalta, jolloin ne muuttuvat vihreiksi, ja lomakkeelle ilmestyy alasvetovalikko. Valikosta valitaan toimenpide "Ennallaan" ja tallennetaan muutokset.   

7.1.4 Siirto
--------------------------
Siirto-toimenpide tehd&auml;&auml;n tieosalle uusien m-arvojen laskemiseksi. Siirtoa k&auml;ytet&auml;&auml;n, kun osa tieosan linkeist&auml; k&auml;sitell&auml;&auml;n jollain muulla toimenpiteell&auml; ja loppujen linkkien m-arvot t&auml;ytyy laskea uudelleen. Esimerkkin&auml; osalle tieosan linkeist&auml; voidaan tehd&auml; lakkautus, lis&auml;t&auml; uusia linkkej&auml; ja pit&auml;&auml; osa linkeist&auml; ennallaan. Siirto tehd&auml;&auml;n tieosoiteprojektiin varatulle tieosalle (varaaminen kuvattu kappaleessa 6) siten, ett&auml; tieosalle on ensin tehty muita toimenpiteit&auml;, kuten lakkautus, uusi tai numerointi. Linkit, joille siirto tehd&auml;&auml;n, valitaan tuplaklikkaamalla ensimm&auml;inen haluttu linkki ja lis&auml;&auml;m&auml;ll&auml; valintaan CTRL + klikkaamalla linkkej&auml;. Sitten valitaan toimenpidevalikosta siirto ja tallennetaan. Siirretyt linkit muuttuvat toimenpiteen tallennuksen j&auml;lkeen punaiseksi. Muutokset n&auml;kyv&auml;t projektin yhteenvetotaulukossa.   


7.1.5 Numeroinnnin muutos
--------------------------
Tieosoitteen numeroinnin muutoksella tarkoitetaan Viitteess&auml; tienumeron ja/tai tieosanumeron muuttamista. 
Projektiin varataan tarvittava(t) tieosa(t), kuten kappaleessa 6 on kuvattu. Varaamisen j&auml;lkeen siirryt&auml;&auml;n toimenpidelomakkeelle Jatka toimenpiteisiin -painikkeella. Valitaan muokattava keltaisella n&auml;kyv&auml; varattu tieosa klikkaamalla kartalta. Tieosa muuttuu vihre&auml;ksi. Viite poimii t&auml;ll&ouml;in koko tieosan mukaan valintaan, vaikkei se n&auml;kyisi kokonaisuuudessaan karttan&auml;kym&auml;ss&auml; ja k&auml;ytt&auml;j&auml;lle tulee t&auml;st&auml; ilmoitus. Mik&auml;li on tarpeen muuttaa vain tietyn linkin numerointia tieosalla, tehd&auml;&auml;n valinta tuplaklikkauksella halutun linkin p&auml;&auml;lt&auml;. Jos valitaan lis&auml;&auml; yksitt&auml;isi&auml; linkkej&auml;, tehd&auml;&auml;n se CTRL+klikkaamalla. Toimenpide-lomakkeelle sy&ouml;tet&auml;&auml;n uusi numerointi (tienumero ja/tai tieosanumero) ja tallennetaan muutokset. Numeroitu osuus muuttuu tallennettaessa ruskeaksi. 
Koska numeroinnin muutos kohdistuu koko tieosaan, muita toimenpiteit&auml; ei tallennuksen j&auml;lkeen tarvitse tehd&auml;. 

7.1.6 K&auml;&auml;nt&ouml;
--------------------------
Tieosoitteen kasvusuunnan voi k&auml;&auml;nt&auml;&auml; Viittess&auml; joko esimerkiksi siirron tai numeroinnin yhteydess&auml;. K&auml;&auml;nt&ouml; tapahtuu tietyiss&auml; tapauksissa automaattisesti ja tietyiss&auml; tilanteissa k&auml;ytt&auml;j&auml; tekee k&auml;&auml;nn&ouml;n manuaalisesti.  

_Automaattinen k&auml;&auml;nt&ouml; siirron yhteydess&auml;:_

Kun siirret&auml;&auml;n tieosa (osittain tai kokonaan) toiselle tieosalle, jolla on eri tieosoitteen kasvusuunta, Viite p&auml;&auml;ttelee siirron yhteydess&auml; kasvusuunnan siirrett&auml;ville linkeille. 

Alla olevassa kuvasarjassa on tehty siirto ja k&auml;&auml;nt&ouml; osalle tieosaa. Projektiin on varattu tie 459 osa 1 ja tie 14 osa 1 (n&auml;kyv&auml;t kartalla keltaisella). Osa tien 14 linkeist&auml; halutaan siirt&auml;&auml; tielle 459 osalle 1 (l&auml;nnen suuntaan), jolloin siirrett&auml;v&auml;t linkit valitaan kartalta. Lomakkeelta valitaan "Siirto" muutosilmoitus (1). Annetaan kohtaan TIE arvoksi kohdetien numero 459. Muut tiedot s&auml;ilyv&auml;t t&auml;ss&auml; tapauksessa samana, mutta my&ouml;s tieosaa, tietyyppia ja jatkuvuuskoodia tulee tarvittaessa muuttaa. Tallennetaan muutokset.(2) Kohde muuttuu siirretyksi ja tieosoitteen kasvusuunta p&auml;ivittyy vastaamaan tien 459 kasvusuuntaan (3). 

T&auml;m&auml;n j&auml;lkeen siirtoon ja k&auml;&auml;nt&ouml;&ouml;n voi valita lis&auml;&auml; linkkej&auml; tielt&auml; 14 tai j&auml;tt&auml;&auml; loput ennalleen. J&auml;lkimm&auml;isess&auml; tilanteessa loput projektiin valitut keltaiset aihiot tulee aina k&auml;sitell&auml; jotta muutosilmoitukset voi l&auml;hett&auml;&auml; Tierekisteriin. T&auml;ss&auml; tapauksessa, mik&auml;li muuta ei tehd&auml;, tulee tie 459 osa 1 valita kartalta ja tehd&auml; sille muutosilmoitus "Siirto" ja painaa Tallenna. Samoin tehd&auml;&auml;n tielle 14 osalle 1. Ilmoitusten yhteenvetotaulu avataan ja mik&auml;li tiedot ovat valmiit, voi ne l&auml;hett&auml;&auml; Tierekisteriin vihre&auml;st&auml; painikkeesta.  

![Siirto ja k&auml;&auml;nt&ouml;](k48.JPG)

_Kuvasarjassa siirret&auml;&auml; osa tiest&auml; 14 tielle 459. Tieosoitteiden kasvusuunnat teill&auml; ovat vastakkaiset, jolloin siirrossa tien 14 kasvusuunta k&auml;&auml;ntyy._



_Manuaalinen k&auml;&auml;nt&ouml; siirron ja numeroinnin yhteydess&auml;:_

Manuaalista k&auml;&auml;nt&ouml;&auml; varten Viitteess&auml; on "K&auml;&auml;nn&auml; kasvusuunta" -painike. Painike aktivoituu lomakkeelle kun k&auml;ytt&auml;j&auml; on tehnyt varaamalleen aihiolle toimenpiteen ja tallentanut sen. Kun k&auml;sitelty&auml; aihiota (on tehty muutosilmoitus siirto tai numerointi) klikataan kartalla, lomakkeella n&auml;kyv&auml;t tehty ilmoitus ja sen tiedot sek&auml; "K&auml;&auml;nnn&auml; kasvusuunta" - painike. Kun sit&auml; klikataan sek&auml; tallennetaan, kasvusuunta k&auml;&auml;ntyy ja yhteenvetotauluun tulee tieto k&auml;&auml;nn&ouml;st&auml; oman sarakkeeseen "K&auml;&auml;nt&ouml;" (rasti ruudussa). 
	
_Kaksiajorataisen osuuden k&auml;&auml;nt&ouml;_

Kun k&auml;&auml;nnet&auml;&auml;n tieosan kaksiajoratainen osuus, se tehd&auml;&auml;n edell&auml; kuvatulla tavalla siirron tai numeroinnin yhteydess&auml; yksi ajorata kerrallaan. Kartalta valitaan haluttu ajorata ja lomakkeelta joko siirto tai numerointi. M&auml;&auml;ritet&auml;&auml;n uusi ajoratakoodi sek&auml; muut tarvittavat tieosoitemuutostiedot lomakkeelle ja tallennetaan. Mik&auml;li tieosoitteen kasvusuunta ei automaattisesti k&auml;&auml;nny (esim. kun k&auml;sitell&auml;&auml;n yht&auml; tieosaa), tehd&auml;&auml;n k&auml;&auml;nt&ouml; manuaalisesti "K&auml;&auml;nn&auml; kasvusuunta" - painikkeella. Yhteenvetotaulussa "K&auml;&auml;nt&ouml;"   
 sarake sek&auml; muutosilmoituksen rivit p&auml;ivittyv&auml;t. 
 
 
7.1.7 Et&auml;isyyslukeman muutos
--------------------------
Tieosoiteprojektissa uudelle tieosoitteistettavalle tieosalle on mahdollista asettaa k&auml;ytt&auml;j&auml;n antama tieosan loppuet&auml;isyyslukema. Ensin valitaan haluttu tieosa kartalta, jonka j&auml;lkeen lomakkeelle ilmestyy kentt&auml;, johon loppuet&auml;isyyden voi muuttaa. Muutettu arvo huomioidaan lomakkeella punaisella huutomerkill&auml;. 


7.1.8 Suunnitelmalinkkien tieosoitteistaminen ja sen jakaminen saksi-ty&ouml;kalulla
--------------------------

__Saksi-ty&ouml;kalun k&auml;ytt&ouml; suunnitelmalinkin jakamiseen__

Saksi-ty&ouml;kalulla voi jakaa suunnitelmalinkin kahteen osaan. Ty&ouml;kalua hy&ouml;dynnet&auml;&auml;n, kun linkin osat halutaan k&auml;sitell&auml; eri toimenpiteill&auml;. Ensin valitaan saksi-ty&ouml;kalu selitteen alaosasta. Seitten ristikursorilla klikataan kartalta tielinkin kohdasta, josta tie jaetaan. Suunnitelmalinkki jakaantuu t&auml;ll&ouml;in A ja B osaan. Sivun oikeaan reunaan avautuu A ja B osille lomake, johon eri toimenpiteet m&auml;&auml;ritell&auml;&auml;n ja tehd&auml;&auml;n tallennus.
Kun osioiden toimenpiteet on tallennettu, suunnitelmalinkin alla sijaitseva nykylinkki tarvittavilta osin lakkautuu automaattisesti oikeasta leikkauskohdasta ja se n&auml;kyy jatkossa C osana lomakkeella. 

7.1.9 Useiden muutosten tekeminen samalle tieosalle
--------------------------

7.1.10 ELY koodin, jatkuvuuden ja tietyypin muutos
--------------------------
Viitteess&auml; voi muokata ~~ELY koodia~~ [ELYn muokkaamista ei ole viel&auml; toteutettu], jatkuvuutta ja tietyyppi&auml;. N&auml;it&auml; muutoksia voi tehd&auml; esimerkiksi Ennallaan muutosilmoituksella, jolloin lomakkeelle tulee alasvetovalikot ELYlle, jatkuvuudelle ja tietyypille. Uudet arvot annetaan valitulle aihiolle ja tallennetaan. Jatkuvuus koodi n&auml;ytet&auml;&auml;n valinnan viimeiselt&auml; linkilt&auml; ja muutokset kohdistuvat my&ouml;s viimeiseen linkkiin. Tietyypin ja ja ELY koodin muutos kohdistuu kaikille valituille linkeille. Ennallaan toimenpiteen lis&auml;ksi n&auml;it&auml; arvoja voi muokata aina, kun ne ovat eri muutosilmoituksen yhteydess&auml; lomakkeella muokattavissa. 


7.2 Muutosilmoitusten tarkastelu taulukkon&auml;kym&auml;ss&auml;
--------------------------

Projektin muutosilmoitusten n&auml;kym&auml;ss&auml; on mahdollista tarkastella ilmoitusten yhteenvetotaulua. "Avaa projektin yhteenvetotaulukko" -painiketta (1) klikkaamalla avautuu taulukkon&auml;kym&auml;, joka kertoo projektissa olevien tieosoitteiden vanhan ja uuden tilanteen sek&auml; tehdyn muutosilmoituksen. Taulukossa rivit on j&auml;rjestetty suurimasta pienimp&auml;&auml;n tieosoitteen mukaan (tie, tieosa, alkuet&auml;isyys, ajorata), jotta saman tien tieosuudet ovat taulukossa per&auml;kk&auml;in suurimmasta pienimp&auml;&auml;. Yhteenvetotaulun AET- ja LET-arvot p&auml;ivittyv&auml;t oikein vasta, kun kaikki tieosan aihiot on k&auml;sitelty. Muutosilmoitusten tekoj&auml;rjestyksell&auml; ei ole vaikutusta lopulliseen yhteenvetotauluun. 

Taulukon saa suurennettua ja pienennetty&auml; sek&auml; suljettua taulukon oikeasta yl&auml;kulmasta (2). Taulukon voi pit&auml;&auml; auki muokatessa ja muutokset p&auml;ivittyv&auml;t taulukkoon tallennettaessa. Viite-sovelluksen voi esimerkisi venytt&auml;&auml; kahdelle n&auml;yt&ouml;lle, joista toisella voi tarkastella muutostaulukkoa ja k&auml;ytt&auml;&auml; karttan&auml;kym&auml;&auml;. Taulukkoa voi liikuttaa tarraamalla siit&auml; osoittimella yl&auml;palkista. Yhteenvetotaulukko ei v&auml;ltt&auml;m&auml;tt&auml; n&auml;y oikein jos selaimen zoom-taso on liian suuri. Ongelma korjaantuu palauttamalla selaimen zoom-taso normaaliksi (Firefox/Chrome Ctrl+0).

![Avaus](k41.JPG)

_Taulukon avaus ja muutosilmoitustalukon n&auml;kym&auml;._

7.3 Tarkastukset
--------------------------

Viite-sovellus tekee tieosoiteprojektissa automaattisia tarkastuksia jotka auttavat k&auml;ytt&auml;j&auml;&auml; valmistelemaan muutosilmoituksen Tierekisterin vaatimaan muotoon. Tarkistukset ovat projektissa jatkuvasti p&auml;&auml;ll&auml; ja reagoivat projektin tilan muutoksiin.  Avatun projektin tarkistusilmoitukset ilmestyv&auml;t k&auml;ytt&auml;j&auml;lle projektissa oikealle tarkistusn&auml;kym&auml;&auml;n "Jatka toimenpiteisiin"-napin painalluksen j&auml;lkeen. Tarkistusilmoituksia voi olla samanaikaisesti auki useita. Tieosoiteprojektin voi l&auml;hett&auml;&auml; Tierekisteriin kun se l&auml;p&auml;isee kaikki tarkistukset eli silloin kun oikealla ei ole yht&auml;&auml;n tarkistusilmoitusta.

![Tarkisilmoitusn&auml;kym&auml;](k49.JPG)

_Tarkistusilmoitukset n&auml;kyv&auml;t projektissa oikealla._


Tarkistusilmoitus koostuu seuraavista kentist&auml;:

|Kentt&auml;|Kuvaus|
|------|------|
|Linkids|Tarkistuksen kohteena oleva yksitt&auml;isen tielinkin ID. Vaihtoehtoisesti linkkien lukum&auml;&auml;r&auml; jos tarkistusilmoitus koskee useampaa linkki&auml;.|
|Virhe|Kuvaus tarkistuksen ongelmatilanteesta|
|Info|Mahdollisia lis&auml;ohjeita tarkistuksen virhetilanteen korjaamiseksi.|

![Tarkistuilmoitus](k50.JPG)

_Tielinkki 6634188 on tieosan viimeinen linkki mutta silt&auml; puuttuu jatkuvuuskoodi "Tien loppu"._ 

Karttan&auml;kym&auml; kohdistuu tarkistusilmoituksen kohteena olevan tielinkin keskikohtaan painamalla "Korjaa"-painiketta. Painamalla Korjaa-nappia uudestaan kohdistus siirtyy seuraavaan tielinkkiin jos sama tarkistus kohdistuu useampaan linkkiin. K&auml;ytt&auml;j&auml; voi nyt valita tarkistusilmoituksen kohteena olevan tielinkin ja tehd&auml; sille tarkistuksen korjaavan toimenpiteen. 

####Tieosoiteprojektissa teht&auml;v&auml;t tarkastukset:####

Tieosoiteprojektiin kohdistuvat tarkistukset:

- Uusi tieosa ei saa olla varattuna jossakin toisessa tieosoiteprojektissa.
- Tieosoitteen kasvusuunta ei saa muuttuua kesken tien.
- Tieosoitteellinen tie ei saa haarautua muuta kuin ajoratakoodivaihdoksessa.
- Ajoratojen 1 ja 2 tulee kattaa samaa osoitealue.
- Tieosoitteelta ei saa puuttua tieosoitev&auml;li&auml; (katkoa m-arvoissa).

Jatkuvuuden tarkistukset:

- Tieosan sis&auml;ll&auml; jatkuvissa kohdissa (aukkopaikka alle 0,1 m), jatkuvuuskoodin tulee olla 5 (jatkuva)

- Tieosan sis&auml;ll&auml; ep&auml;jatkuvuuskohdissa (aukkopaikka yli 0,1 m) jatkuvuuskoodi tulee olla 4 (liev&auml; ep&auml;jatkuvuus). 
Tieosan sis&auml;isen ep&auml;jatkuvuuden pituudelle ei ole asetettu yl&auml;rajaa.

- Tieosan lopussa tulee olla jatkuvuuskoodi 2 (ep&auml;jatkuva) tai 4 (liev&auml; ep&auml;jatkuvuus), jos ennen tien seuraavaa tieosaa on ep&auml;jatkuvuuskohta. Seuraavaa tieosaa ei ole v&auml;ltt&auml;m&auml;tt&auml; valittu projektiin, joten tarkistus huomioi my&ouml;s projektin ulkopuoliset tieosat.

- Tieosoitteen viimeisell&auml; (suurin tieosanumero) tieosalla tulee olla jatkuuvuuskoodi 1 (tien loppu)

- Jos tieosoitteen viimeinen tieosa lakkautetaan kokonaan, niin tien edellisell&auml; tieosalla tulee olla jatkuvuuskoodi 1 (tien loppu), t&auml;t&auml; tieosaa ei ole v&auml;ltt&auml;m&auml;tt&auml; valittu projektiin, joten tarkistus ulottuu my&ouml;s projektin ulkopuolisiin tieosiin.

- Jos tieosan seuraava tieosa on eri ELY-koodilla, niin jatkuvuuskoodin tulee olla tieosan lopussa 3 (ELY raja).

7.4 Muutosilmoitusten l&auml;hett&auml;minen Tierekisteriin
--------------------------

Muutosilmoitus vied&auml;&auml;n Tierekisteriin avaamalle ensin Yhteenvetotaulukko klikkaamalla oikean alakulman sinist&auml; Avaa yhteenvetotaulukko -painiketta. Kun projektilla ei ole en&auml;&auml; korjaamattomia tarkistusilmoituksia, aktivoituu vihre&auml; Tee tieosoitemuutosilmoitus -painike. Painikkeen painamisen j&auml;lkeen sovellus ilmoittaa muutosilmoituksen tekemisest&auml; "Muutosilmoitus l&auml;hetetty Tierekisteriin." -viestill&auml;.

![Muutosilmoituksen painike](k38.JPG)

_Muutosilmoituspainike oikeassa alakulmassa._

Kun muutosilmoitus on l&auml;hetetty, muuttuu projektilistauksessa ko. projektin Tila-tieto statukselle "L&auml;hetetty tierekisteriin". Viite-sovellus tarkistaa minuutin v&auml;lein Tierekisterist&auml;, onko muutos k&auml;sitelty Tierekisteriss&auml; loppuun asti. Kun t&auml;m&auml; on tehty onnistuneesti, muuttuu Tila-tieto statukselle "Viety tierekisteriin". T&auml;ll&ouml;in tieosoiteprojekti on viety onnistuneesti Tierekisteriin, ja se on valmis. Mik&auml;li muutosilmoitus ei ole mennyt l&auml;pi tierekisteriin, lukee tilana "Virhe tierekisteriss&auml;" ja listalla on oranssi painike "Avaa uudelleen". Tarkemmin virheen tiedot p&auml;&auml;see tarkistamaan viem&auml;ll&auml; hiiren "Virhe tierekisteriss&auml;" -tekstin p&auml;&auml;lle, jolloin infolaatikko virheest&auml; tulee n&auml;kyviin. Virhe korjataan avaamalla projekti oranssista painikkeesta ja tekem&auml;ll&auml; tarvittavat muokkaukset sek&auml; l&auml;hett&auml;m&auml;ll&auml; ilmoitukset uudelleen tierekisteriin.  

Projektia ei voi muokata, kun sen tila on joko "L&auml;hetetty tierekisteriin", "Tierekisteriss&auml; k&auml;sittelyss&auml;" tai "Viety tierekisteriin."

|Tieosoiteprojektin tila|Selitys|
|-|-|
|Keskener&auml;inen|Projekti on ty&ouml;n alla ja sit&auml; ei ole viel&auml; l&auml;hetetty tierekisteriin.|
|L&auml;hetetty tierekisteriin|Projekti on l&auml;hetetty tierekisteriin.|
|Tierekisteriss&auml; k&auml;sittelyss&auml;|Projekti on tierekisteriss&auml; k&auml;sittelyss&auml;. Tierekisteri k&auml;sittelee projektin sis&auml;lt&auml;mi&auml; muutosilmoituksia.|
|Viety tierekisteriin|Projekti on hyv&auml;ksytty tierekisteriss&auml;. Muutokset n&auml;kyv&auml;t my&ouml;s Viite-sovelluksessa.|
|Virhe tierekisteriss&auml;|Tierekisteri ei hyv&auml;ksynyt projektia. Tierekisterin tarkempi virheilmoitus tulee n&auml;kyviin viem&auml;ll&auml; osoittimen "Virhe tierekisteriss&auml;"-tekstin p&auml;&auml;lle. Projektin voi avata uudelleen.|
|Virhetilanne Viitessa|Projekti on l&auml;hetty Tierekisteriin ja se on Tierekisterin hyv&auml;ksym&auml;, mutta projektin tiedot eiv&auml;t piirry Viite-sovelluksessa.| 

![Tila-statuksia](k39.JPG)

_Tila-statuksia tieosoiteprojektit -listassa._
