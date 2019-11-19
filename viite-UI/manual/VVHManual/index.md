Digiroad - Väyläverkon hallinta (VVH) -sovelluksen käyttöohje
======================================================

1. Yleistä
-----------------------

Väyläverkon hallinta (VVH) -sovellus on osa Digiroad-tietojärjestelmää. VVH-sovelluksessa hallinnoidaan linkki-solmu -mallista väyläverkkoa (geometriaa). Digiroad-tietojärjestelmä koostuu ominaisuustietojen hallinta -sovelluksesta (OTH) ja VVH-sovelluksesta yhdessä. VVH tarjoaa OTH:lle geometriatiedot rajapinnan kautta, eikä geometriaa hallinnoida OTH-sovelluksen puolella.

VVH-sovellus tarjoaa geometriatiedot myös Viite-sovellukselle. Uudessa Viite-sovelluksessa hallinnoidaan Väylän tieosoiteverkkoa.

Tässä ohjeessa käsitellään ainoastaan Väyläverkon hallinnan sovellusta.

VVH-sovellus tarjoaa geometrian ylläpitoon erilaisia työkaluja, joiden avulla geometriaa voidaan ladata tietokantaan ja muokata:

1. Maanmittauslaitoksen Maastotietokannasta tulevat MTK-päivitykset (muutostiedot) ladataan automaattisesti joka yö klo 23.00*. __Tämä prosessi on kehitystiimin vastuulla,__ koska se tapahtuu automatisoidusti, ja päivitysten toiminnasta vastaa sovellustoimittaja (CGI). MML:n toimittamaa aineistoa ei voi myöskään muokata VVH-sovelluksessa.

1. Suunnitelmageometrian tallennetaminen VVH:n tietokantaan sovelluksen avulla. Työ tehdään manuaalisesti. Olennaisessa osassa tätä on VVH-työnohjaus (workflow), jonka mukaan käyttäjän toimenpiteet etenevät sovelluksessa.

1. Täydentävän geometrian tallennetaminen VVH:n tietokantaan sovelluksen avulla. Työ tehdään manuaalisesti. Olennaisessa osassa tätä on VVH-työnohjaus (workflow), jonka mukaan käyttäjän toimenpiteet etenevät sovelluksessa.

Väyläverkon hallinta -sovellus on toteutettu Esri ArcGIS-teknologian päälle. Käytettävä ArcGIS-sovellus on ArcMap 10.3. ArcEditor-lisenssillä. Sovellusta käytetään Väylän Citrix-palvelun kautta. Sovelluksessa on käytössä myös kaikki ArcEditorin tavalliset toiminnot, eikä niitä käsitellä tässä ohjeessa erikseen. Ohje olettaa, että käyttäjälle ovat ArcMapin perustoiminnallisuudet tuttuja.

1.1 Yhteystiedot ja käyttäjätunnukset
--------------------------

VVH-sovelluksen käyttöä varten tulee olla pääsy Väylän Citrix-palveluun, jonka lisäksi annetaan erikseen oikeudet VVH-sovellukseen.

__Käyttäjätunnukset Citrix-palveluun:__ Väylän oman yhteyshenkilön kautta
__Käyttäjätunnukset VVH-sovellukseen ja sovellustuki:__ Digiroad-operaattori, info@digiroad.fi

1.2 Termit ja lyhenteet
--------------------------

__Hallinnollinen luokka__
Tien omistaja (valtio, kunta, yksityinen tai ei tiedossa), ylläpidosta vastaa MML.

__Linkki-solmu verkko tai linkki-solmu malli__
Tielinkkien ja tiesolmujen muodostoma topologisesti eheä verkko. Tiesolmut liittävät tielinkit toisiinsa.

__Kohdeluokka (tai tieluokka)__
Maanmittauslaitoksen käyttämä luokittelu linkkien hierarkialle, esimerkiksi Autotie Ia, Autotie Ib, ajopolku jne. Digiroadissa vastaava termi on toiminnallinen luokka (+linkkityyppi), mutta nämä eivät vastaa toisiaan suoraan. Jokaisella tielinkillä on kohdeluokka.

__MML__
Maanmittauslaitos

__MTK__
Maanmittauslaitoksen Maastotietokanta.

__Suravage__
Suunniteltu rakentamisvaiheen geometria. Suravage-geometrioita tallennetaan VVH:n tietokantaan työnohjauksen (workflown) mukaisesti VVH:n ja ArcMapin työkaluilla.

__Suunnitelmalinkki__
Suunnitelmalinkki-tasolla ovat Suravage-geometrian tielinkit. Suunnitelmatyönä tehdyt työlinkit siirtyvät suunnitelmalinkki-tasolle checkinin yhteydessä, ja poistuvat työlinkki-tasolta.

__Suunnitelmasolmu__
Suunnitelmasolmu-tasolla ovat Suravage-geometrian solmut. Suunnitelmatyönä tehdyt työsolmut siirtyvät suunnitelmasolmu-tasolle checkinin yhteydessä, ja poistuvat työsolmu-tasolta.

__Tielinkki tai linkki__
MML:n ja siten VVH:n tiegeometrian perusyksikkö. Yhteen tielinkkiin voi liittyä 2-n tiesolmua. Tielinkkejä ei voi muokata VVH-sovelluksessa

__Tiesolmu tai solmu__
Käytetään kuvaamaan kahden tai useamman tielinkin kytköstä, tielinkin alkua tai päätepistettä. Jokaisella tielinkillä on alkusolmu ja loppusolmu. Solmujen hallinta on automaattista, eli VVH-sovellus lisää ja poistaa ne sekä täydentää niiden ominaisuustiedot automaattisesti. Yhteen solmuun voi liittyä 1-n tielinkkiä.

__Työ__
VVH:n työnohjauksen ilmentymä. Työn sisällä tehdään kaikki muutokset, jotka halutaan ko. työstä tallentaa tietokantaan.

__Työalue__
Työn rajaava aluerajaus (polygoni). Työn luominen alkaa työalueen rajauksella. Työhön liittyvät uudet kohteet luodaan tämän työalueen sisällä.

__Työlinkki__
Työlinkki on linkki, joka luodaan työn aikana. Työlinkki-tasolla linkit (esim. suunnitelmalinkit) ovat muokattavissa.

__Työnohjaus, workflow__
Käyttäjän toiminta sovelluksessa uusien linkkien digitoinnin osalta etenee VVH:n työnohjauksen mukaan. Käyttäjä ei voi tallentaa uusia tielinkkejä tietokantaan muuten, kuin työnohjauksen mukaisesti. Järjestelmä estää työnohjauksesta poikkeavan toiminnan.

__Työsolmu__
Työsolmu on solmu, joka luodana työn aikana. Työsolmu-tasolla solmut ovat muokattavissa. VVH-järjestelmässä solmujen hallinta on automatisoitu.

__Täydentävä linkki__
Täydentävä linkki -tasolla ovat täydentävän geometrian tielinkit. Täydentävän geometrian työnä tehdyt työlinkit siirtyvät täydentävä linkki -tasolle checkinin yhteydessä, ja poistuvat työlinkki-tasolta.

__Täydentävä solmu__
Täydentävä solmu -tasolla ovat täydentävän geometrian solmut. Täydentävän geometrian työnä tehdyt työsolmut siirtyvät täydentävä solmu -tasolle checkinin yhteydessä, ja poistuvat työsolmu-tasolta.

__Verteksi__
Tielinkin taitepistet (digitoidut pisteet). Tielinkillä voi olla 2-n verteksiä riippuen digitoinnin tarkkuudesta.

1.2.1 Kohteiden ID-hallinta
--------------------------

Järjestelmä huolehtii ID-hallinnasta automaattisesti. Alle on kerrottu, mitä eri ID:t VVH-sovelluksessa tarkoittavat.

__OBJECTID:__ ESRI:n GeoDatabase -kohdeluokan sisäinen tunniste. Geodatabase ylläpitää. Ei käytetä VVH:ssa
__MTKID:__ Maastotietokannan tieviivan tunniste. Siirretään sellaisenaan VVH:n tielinkille.
__DRID:__ VVH:n sisäinen tunniste. Käytetään mm. kohteiden välisiin relaatioihin ja historian hallintaan. Päivityssovellus luo ja ylläpitää. DRID vaihtuu vain jos MTKID ja LinkID vaihtuvat. Järjestelmä huolehtii DRID:n hallinnasta.
__LINKID:__ VVH:n linkkitunniste. Yksittäisen VVH-tielinkin identifioiva tunniste, joka muuttuu kun tieverkon  topologia on muuttunut tai tielinkin geometria on oleellisesti muuttunut. Järjestelmä huolehtii linkki ID:n hallinnasta.
__NODEID:__ VVH:n solmutunniste. Yksittäisen VVH-tiesolmun identifioiva tunniste, joka muuttuu kun solmun sijainti on   muuttunut ja solmuun yhdistyvien tielinkkien lukumäärä on muuttunut. Järjestelmä huolehtii Node ID:n hallinnasta.

DRID, LinkiD ja NodeID muodostetaan kun kohde luodaan ensimmäistä kertaa VVH-kantaan. Kohteiden päivittyessä DRID pysyy aina samana. LinkID voi muuttua perustuen linkin muutokseen (linkin ID:n elinkaaren hallinta).

1.3 Työskentelyn aloitus
--------------------------

Väyläverkon hallinnan sovellukseen kirjaudutaan osoitteesta https://citrix.vayla.fi/. Valitaan Apps ->  ArcMap __Standard__.

![ArcMap.](k1.png)

_ArcMap Standard -kuvake Citrixissä._

Nämä työvaiheet tehdään vain kerran, minkä jälkeen Digiroad-työkalujen tulisi olla käytössä aina, kun ArcMap on avattu.

1. Avaa ArcMap

1. Valitse Customize -> Add-In Manager ja Options-välilehti

1. Valitse Add Folder..

1. Kirjoita avautuvaan ikkunaan kohtaan Kansio: \\\172.17.204.39\VVH$
	-Huom! Jos Folder-kenttään kirjoitetun osoitteen perässä on välilyönti, ei toimi oikein
	
1. Paina OK ja Close.

1. Valitse Customize -> Toolbars ja ruksi Digiroadin työkaluvalikot eli Digiroad Editor ja Digiroad Tools

1. Sulje ArcMap ja avaa uudelleen. Nyt Digiroad-työkaluvalikot ovat valmiina käytettävissä.


2. Digiroad Tools: kirjautuminen ja perustyökalut
--------------------------

Digiroad toolbar -työkalupalkki sisältää aputyökaluja aineiston hallintaan.

![Digiroad Tools.](k2.png)

_Digiroad Tools._

2.1 Kirjautuminen järjestelmään 
--------------------------

Jos järjestelmä ei ArcMappia avattaessa kysy kirjautumistietoja, saa kirjautumisen auki Digiroad Tools -> Digiroad: Login. Kirjautuminen on pakollista, jotta VVH:n työkaluja voi käyttää. Oletuksena kirjautuminen tapahtuu VVH:n tuotantokantaan, jolloin osoitteen pääte on /vvh.

Kirjautumiseen käytetään Väylän Citrixiin käytettävää käyttäjätunnusta (A-, U-, LX-, L- tai K-alkuinen) ja __erikseen VVH-sovellukseen määritettyä salasanaa__. Salasana ei siis ole sama, kuin Citrixiin kirjauduttaessa. Oletussalasana on kerrottu käyttäjälle VVH-sovelluskoulutuksen yhteydessä.

![Kirjautuminen järjestelmään.](k3.png)

_Kirjautuminen järjestelmään._

Jos käyttäjä haluaa kirjautua muihin tietokantoihin, se tapahtuu klikkaamalla neliöitä kirjautumisikkunan vasemmassa reunassa, ja valitsemalla avautuvasta alapalkista alasvetovalikosta haluttu tietokanta. Kaikkiin tietokantoihin on omat kirjautumistiedot, joten käyttäjällä ei oletuksena ole pääsyä kaikkiin kantoihin.

Jos järjestelmä pyytää Login-vaiheessa vaihtamaan salasanan, sen voi tehdä heti tai vasta myöhemmin painamalla Cancel. Salasanan voi vaihtaa myöhemmin myös Digiroad: Options-valikosta (kts. alempaa).

Kirjautuessa järjestelmä ilmoittaa myös, jos MTK-päivitykset eivät ole ajossa tai jokin MTK-päivitys ei ole mennyt läpi. Näihin voi painaa OK. VVH:n kehitystiimi huolehtii päivitysten ajosta.

![Ilmoitus.](k4.png)

_Järjestelmä ilmoittaa käyttäjälle kirjautumisen yhteydessä, jos MTK-päivitykset eivät ole ajossa. Näin kaikki sovelluksen käyttäjät tietävät asiasta._

![Ilmoitus.](k5.png)

_Järjestelmä ilmoittaa käyttäjälle, jos jokin MTK-päivitys ei ole mennyt läpi._

2.2 Add Layers
--------------------------

Add Layers -työkalulla voi lisätä järjestelmässä valmiiksi olevia karttatasoja kartalle. Add Layersin saa auki Digiroad Tools -alasvetovalikosta tai sen oikealla puolella olevasta kuvakkeesta. Haluttu karttataso ruksitaan valikosta ja painetaan Apply. Tasot tulevat näkyviin kartalle ja sisällysluetteloon (Table of Contents). OK-painikkeesta tasot lisätään kartalle ja Add Layers sulkeutuu. Add Layersin voi sulkea myös ruksista.

Add Layers -valikossa ylimpänä ovat “perusgeometriatasot”, jotka ovat Maanmittauslaitoksen Maastotietokannasta. Lisäksi valittavana ovat historiatasot, suunnitelmalinkkeihin liittyvät tasot, kuntarajat, maakuntarajat, Ely-rajat, Väylän raiteet sekä erilaisia MML:n taustakarttoja WMTS-tasoina.

Add Layerisistä voi valita myös vain tietyn kunnan kohteet tielinkki-tasolta lisättäväksi municipality alasvetovalikosta (oletuksena koko Suomi) ja zoomata ko. kuntaan valitsemalla ruksin kohtaan Zoom to selected.

__Huomio tasojen lisäämisestä:__ ArcMap lopettaa piirron painamalla Esc-näppäintä. Jos lisää esim. tielinkki-tason siten, että zoomaus on koko Suomeen, kestää tason piirtyminen melko kauan. Piirto katkeaa Escillä. Usein ennen isojen aineistojen lisäämistä on hyvä zoomata johonkin pienelle alueelle, niin piirto ei kestä kauan. Milloin tahansa työskentelyn aikana Esc-näppäimestä on hyötyä, jos ei halua odottaa piirtoa loppuun asti.

Jo lisätyt tasot värjäytyvät sinisellä Add Layersissä, mutta se ei estä lisäämästä niitä uudelleen.

![Add Layers.](k6.png)

_Add Layers -valikosta voi lisätä järjestelmässä valmiina olevia karttatasoja._

2.3 Feature Drawing
--------------------------

Feature Drawing avataan Digiroad tools -alasvetovalikon kautta. Feature Drawing -ikkunan avulla karttatasoille voidaan lisätä erilaisia piirtoelementtejä, kuten digitointisuuntaa havainnollistavat nuolet.

Digitointisuunnan piirrosta on apua esimerkiksi silloin, kun Suravage-aineistolle on tarkoitus määrittää yksisuuntaisuus-tietoja. Yksisuuntaisuudet tallennetaan suhteessa linkin digitointisuuntaan.

Feature Drawing tukee seuraavia elementtejä:

1. Draw Start Points piirtää ensimmäisen verteksin.
1. Draw Vertices piirtää kohteiden kaikki verteksit paitsi ensimmäisen ja viimeisen.
1. Draw End Points piirtää viimeisen verteksin.
1. Draw Direction Arrows piirtää suuntanuolen osoittamaan viivan digitointisuuntaa. Huomaa, että suuntanuolen symbolin kulma tulee asettaa niin, että se osoittaa vasemmalta oikealle (oletusasetus on näin, ok)

![Feature Drawing.](k7.png)

_Feature Drawing._

Jokaisen elementin symboli voidaan asettaa erikseen käyttäen ArcMapin symbolieditoria. Symbolieditoriin pääsee kunkin elementin Edit-painikkeesta.

Halutut symbolit lisätään kartalle Apply-painikkeesta. Symbolit piirretään karttatasolle niin kauan, kunnes käyttäjä ne poistaa tai ArcMap istunto suljetaan. Feature Drawing -ikkunan sulkeminen ei poista symboleita. Erikoispiirrot voi poistaa kaikilta karttatasoilta myös painamalla Reset-painiketta.

![Feature Drawing.](k8.png)

_Feature Drawing -työkalulla saadaan piirtoon mm. kohteiden digitointisuunnat._

2.4 Options
--------------------------

Options-valikko avataan Digiroad Tools -alasvetovalikosta. Digiroad Options - ikkuna on asetusikkuna erilaisten työkalujen ja toiminnallisuuksien asetusten hallintaan käyttäjäkohtaisesti. Ikkunan avulla voidaan asettaa mm. “Flash selected geometries” -valinta Tools -välilehdeltä.

Optionsin valikoihin kannattaa tutustua, jotta työskennellessä voi valita sieltä itselleen sopivat asetukset.

![Options.](k9.png)

_Digiroad: Options -valikon asetuksia. Oikealla Editor Extension -välilehti, josta löytyy esimerkiksi Editor trackiin liittyvät käyttäjäkohtaiset asetukset._

###Salasanan vaihtaminen###

Salasanan vaihtaminen onnistuu Digiroad: Options -valikon General-välilehdeltä kohdasta Change Password.

2.5 Muita perustyökaluja
--------------------------

###Feature Coordinates###

Feature Coordinates -työkalulla voi tarkastella aineiston karttakoordinaatteja. Toiminto on suunniteltu toimimaan mahdollisimman yhdenmukaisesti ArcMap-vakiotyökalu Feature Infon kanssa. Erona on se, että Feature Info avaa ikkunan kohteiden ominaisuuksien tarkasteluun ja Feature Coordinates vastaavasti avaa ikkunan kohteiden koordinaattien tarkasteluun.    

Tätä työkalua voi käyttää myös esimerkiksi siihen, kun tutkii Suravage-geometrian z-koordinaattien arvoja.

Työkalun saa auki valitsemalla Digiroad Toolsista Feature Coordinates, työkalupalkista Feature Coordinates -työkalun

![Feature Coordinates.](k10.png)

ja valitsemalla sitten kohteita kartalta.

Työkalulla voidaan valita:

1. Yksittäisiä kohteita klikkaamalla kohdetta kartalta
1. Useita kohteita piirtämällä suorakaide
1. Yksittäisiä tai uusia kohteita säilyttäen aikaisemman valinnan kohteet käyttämällä CTRL -näppäintä

![Feature Coordinates.](k11.png)

_Feature Coordinates -työkalu._

Ikkuna koostuu kahdesta paneelista ja kohteiden valintalistasta. Ylempi paneeli listaa kaikki valitut kohteet ja mahdolliset kohteiden osat. Alempi paneeli listaa valitun kohteen karttakoordinaatit. Valintalista (Coordinates from) määrää työkalun valintoihin käyttämän kartattason.

Vaihtoehdot ovat Feature Info -työkalun mukaiset eli:

1. Sisällysluettelon ylimmäinen  karttataso Top-most Layer
1. Näkyvät karttatasot Visible Layers
1. Valittavissa olevat karttatasot Selectable Layers
1. Yksittäinen valittu karttataso, nyt valittuna “Tielinkki (kohdeluokka)”

Ylemmän paneelin kohdelistasta (nyt Sotkamontie tai OID-arvoilla merkattu kohde) valitaan aktiivinen kohde tai osa, jonka koordinaatit listataan alempaan paneeliin. Kohteiden tarkastelussa helpottaa, jos Digiroad Options -valikosta on valittuna Tools -välilehdeltä “Flash selected geometries”, jolloin sovellus väläyttää kartalla kohdetta, jota klikataan ylemmästä paneelista. Tuplaklikkamalla kohdetta voidaan kartta kohdistaa kohteeseen.

Alemman paneelin lista sisältää X, Y, M ja Z koordinaatit. Saraketta voidaan käyttää lajittelun kautta esim. kun halutaan löytää verteksejä, jotka ovat erityisen lähellä tai kaukana toisistaan (esim. kun tutkitaan laaduntarkistusajojen tuloksia VVH:n kehitystiimissä).

###Feature Differences###

Feature Differences -työkalulla voi tarkastella aineiston kohteiden ominaisuustietojen eroja. Toiminto on suunniteltu toimimaan mahdollisimman yhdenmukaisesti ArcMap-vakiotyökalu Feature Infon kanssa. Erona se, että Feature Info avaa yhden kohteen kaikki ominaisuudet, kun taas Feature Differences listaa kaikkien valittujen kohteiden ominaisuuksien erot. 

Työkalun saa auki valitsemalla Digiroad Tools -valikosta Feature Differences, työkalupalkista  Feature Differences -työkalun

![Feature Differences.](k12.png)

ja valitsemalla sitten kohteita kartalta. Kohteiden valinta suoritetaan samalla tavalla kuin Feature Info ja Feature Coordinates -työkaluilla. Kohdekarttataso valitaan alasvetovalikosta, eli minkä karttatason kohteiden eroja halutaan tarkastella (tielinkit, tiesolmut tms.)

Rivit, joilla arvot eroavat, on väritetty keltaisella taustavärillä (kuva alla). Sarakkeita on yhtä monta, kuin kartalta valittuja kohteita (Feature 1, Feature 2 jne.) ja ne ovat kaikki mukana tarkastelussa. Tämän avulla voi esimerkiksi tarkastella viereisten ajoratojen ominaisuustietojen mahdollisia eroavaisuuksia.

![Feature Differences.](k13.png)

_Feature Differences -työkalu._

Työkalun Options -valikosta voidaan valita mitä listalla näytetään:

1. Use Field Alias käyttää kohdeluokan kentän nimen sijaan GeoDatabase -kentän alias nimeä. Esimerkiksi kohtaan MTK-ID tulee teksti MTK-tunniste, JOBID kohtaan työn tunniste jne.
1. Use Domain Values näyttää todellisten tietokanta-arvojen sijaan kentän arvoille sanalliset selitteet.
1. Drop Fields With Equal Values suodattaa listalta kaikki kentät, joiden arvot ovat kaikilla valituilla kohteilla samat.

Jos valinnat Use Field Alias ja Use Domain Values ovat valittuna, tulee yllä olevasta valinnasta selkeämmin luettava:

![Feature Differences.](k14.png)

_Feature Diffrences -työkalu, kun Use Field alias ja Use domain values -valinnat ovat käytössä._
 
Klikkaammalla kohteen sarakkeen otsikkoa, kohdetta voidaan väläyttää näkymässä tai tuplaklikkaamalla kohdistaa kohteeseen. 

3. Digiroad Editor ja editoinnin työnohjaus (workflow)
--------------------------

Digiroad Editor -työkaluvalikko sisältää työn ohjaukseen ja työn kulkuun liittyviä toiminnallisuuksia. Osa työnohjauksen asetuksista voi muuttaa käyttäjäkohtaisesti Digiroad Options -valikosta Workflow -välilehdeltä.

Tässä ohjeessa keskitytään suunnitelma-aineistojen työnohjaukseen. Sovelluksessa ylläpidetään myös täydentävää geometriaa, jonka työnohjaus on täysin samanlainen. Tarvittaessa käyttäjä voi täydentävän geometrian osalta tapauksesta riippuen ohittaa osan vaiheista (esim. ominaisuustietojen täydellinen täyttäminen).

Työkalut esitellään työnohjauksen (Workflow) mukaisessa järjestyksessä, eli siinä järjestyksessä jossa käyttäjä etenee VVH-sovelluksessa suunnitelma-aineistoja käsittellessään. Kuitenkin käytännössä esimerkiksi validointeja tulee tehtyä työskentelyn aikana useita kertoja. Kaikki työn vaiheet voikin toistaa tarpeen mukaan käytännössä useita kertoja, paitsi työalueen luomisen ja työn merkkaamisen valmiiksi.

1. Työalueen luominen: Create a Job
Määritellään työalue (polygoni), jonka alueella aiotaan työskennellä.

1. Työn rekisteröinti ja ominaisuuksien määrittely ja työn avaus: Register New Job
Työ rekisteröidään ja siihen liitetään tarvittavat liitteet ja muut lisätiedot.

1. Työn ulosmerkkaus: Digiroad: Checkout
Työalueella olevat suunnitelmalinkit ja -solmut siirretään suunnitelmalinkki-tasolta muokattavalle tasolle.

1. Aineiston muokkaus: Start editing
Varsinainen editointi eli kohteiden luominen ja muokkaaminen.

1. Validointi: Feature Validation
Kohteiden validointi, jotta ne ovat topologisesti eheitä ja sisältävät vähimmäistiedot.

1. Aineiston sisäänmerkkaus: Digiroad: Checkin
Validoidun aineiston palauttaminen tietokantaan ns. primääritasolle, jossa sitä ei voi muokata.

1. Työn merkkaus valmiiksi
Checkin ei sisällä työ merkkausta valmiiksi. Työ merkataan valmiiksi (Complete Job) vasta sen jälkeen, kun siihen ei varmasti ole enää tulossa muutoksia.

	Lisäksi käyttäjä voi hallita töitä ja työjonoa sekä päivittää työalueen tarvittaessa:

1. Töiden ja työjonon hallinta: Digiroad: Suspend Job ja Digiroad: Work Queue
Töiden keskeyttäminen ja jatkaminen sekä olemassa olevien töiden hallinta.

1. Työalueen päivittäminen

Alla on kuva VVH:n työnkulusta (Digiroad VVH Workflow), sekä työlinkkien ja työsolmujen siirtymisestä muokattavalle tasolle ja pois muokattavalta tasolta (VVH Checkout ja Checkin).

![VVH työnkulku.](k15.png)

_VVH työnkulku. Aineistolle voi tehdä checkoutin ja checkinin niin monta kertaa, kuin on tarpeen. Vasta Complete Job tekee työstä valmiin, ja sitä ei voi enää palata editoimaan._

![VVH työnkulku.](k16.png)

_Suunnitelmalinkkien ja -solmujen siirtyminen eri tasojen välillä checkoutin ja checkinin yhteydessä. Aineistolle voi tehdä checkoutin ja checkinin niin monta kertaa, kuin on tarpeen. Vasta Complete Job tekee työstä valmiin, ja sitä ei voi enää palata editoimaan._

3.1 Työalueen luominen: Create a Job
--------------------------

Työalueen voi luoda kahdella eri tavalla:

1. Create Job Area: Painike

	![painike.](k17.png) 

	jonka jälkeen alue piirretään kartalle. Piirto loppuu tuplaklikkaamalla.

1. Create Job: Valitsemalla kartalta ArcMapin Selection-työkalulla tielinkkejä ja luomalla työalueen tielinkkien kattamalta alueelta valitsemalla painike 

	![painike.](k18.png) 

Työalueen piirtämisen jälkeen rekisteröinti-ikkuna avautuu. Työalueen kokoa ei ole pakollista määrittää tarkasti tässä vaiheessa. Työalue päivittyy silloin, jos käyttäjä digitoi työlinkkejä työalueen rajan yli tai sen ulkopuolelle. Työaluetta voi päivittää myöhemmin myös manuaalisesti, kts. kohta 3.8 Työalueen päivittäminen.

3.2 Työn rekisteröiminen: Register Job
--------------------------

Työn rekisteröinnissä annetaan työn perustiedot, kuten tyyppi (alasvetovalikosta) ja nimi. Suravage-aineistoilla työn tyyppi on aina Suunnitelma-aineiston muokkaus ja nimeksi annetaan hankkeen nimi, esim. “Vt 6 TaaLa”. Työn kesto ja prioriteetti eivät ole olennaisia tietoja, joten niiden sisältöön ei tarvitse puuttua. Kohtaan notes on hyvä kirjoittaa mahdolliset erityispiirteet ko. työstä. 

Rekisteröinnissä lisätään myös työhön liittyvät tasot (Layers) ja liitteet (Attachments) rekisteröinti-ikkunan muiden välilehtien kautta. Liitteiksi voidaan lisätä esimerkiksi suunnitelmaan liittyviä PDF-tiedostoja. Shift-painike pohjassa voi valita useita liitteitä kerrallaan.

![Rekisteröinti.](k19.png) 

_Työn rekisteröinti. Layers ja Attachment -välilehdillä lisätään työssä tarvittavat tasot ja liitteet._

Työ avautuu automaattisesti painikkeesta Register, jos ruksia Open this job immediately ei poista. Rekisteröinnin jälkeen sovellus kohdistaa työalueeseen. Sovellus myös luo automaattisesti työalueelle tasot työsolmu ja työlinkki, jotka ovat tyhjiä. Tasot tulevat näkyviin sisällysluetteloon (Table of Contents). Kun työhön digitoidaan uusia kohteita, ne tallentuvat näille kyseisille tasoille, eivät siis samalle tasolle MTK-tielinkkien ja niistä luotujen solmujen kanssa.

3.3 Työn ulosmerkkaus: Digiroad: Checkout
--------------------------

Työn ulosmerkkauksessa kopioidaan työalueella olevat suunnitelmalinkit editoitavalle aineistotasolle. Checkoutissa työlinkki-tasolle tulevat ne kohteet, joiden työnumero (JOBID) on sama kuin ko. työn tai JOBID on null (työ on poistettu, mutta checkin tehty). Vastaavasti työsolmu-tasolle tulevat suunnitelmasolmu-tasolta ko. suunnitelmalinkkeihin liittyvät solmut. 

Käytännössä siis kun ensimmäistä kertaa ko. alueella työskennellään, ei ulosmerkkauksessa tule kartalle vielä mitään kohteita ellei työalueella ole suunnitelmalinkkejä, joiden JOBID on null. Merkitys muuttuu, kun checkin on kerran tehty, mutta työn editointia halutaan jatkaa. VVH-järjestelmä kuitenkin vaatii checkoutin tekemisen myös ensimmäisellä kerralla, kun työ aloitetaan.

Huomioitava on, että jos samalla alueella on jollain toisella JOBID:llä suunnitelmalinkkejä, eivät ne tule työlinkki-tasolle checkoutissa. Näiden linkkien olemassaolon voi helposti tarkistaa laittamalla piirtoon suunnitelmalinkki-tason kohteet. Muissa töissä olevia työlinkkejä ei saa näkyviin kartalle.

Checkout suoritetaan Digiroad Editor-valikosta valitsemalla kohta Digiroad: Checkout. Ilmestyvästä ikkunasta (alla) painetaan Checkout. Tämä kestää hetken. Kun Checkout on valmis, ilmestyy ikkunaan teksti “Checkout Completed!” Ikkunan voi sulkea ruksista.

![Ulosmerkkaus.](k20.png) 

_Checkout. Checkout hakee työalueelta kaikki kohteet, joilla on sama JOBID tai JOBID null. Käytännössä kun työlle tehdään ensimmäistä kertaa checkout, ei se palauta kartalle uusia kohteita._

Checkoutin voi perua tarvittaessa kohdasta Undo Checkout (palauttaa työlinkit ja työsolmut tietokantaan suunnitelmalinkki- ja solmu tasoille).

Checkoutin jälkeen voidaan aloittaa työn kohteiden muokkaaminen.

3.4 Työn muokkaus: Start Editing
--------------------------

Editointi aloitetaan valitsemalla Digiroad Editor valikosta Start Editing. __Editointia ei saa aloittaa ArcMapin vakioeditorilla, koska tällöin editoitavaksi ei tule oikea taso.__ 

Editointitilassa on mahdollista luoda uusia kohteita, muokata niitä sekä muokata kohteiden ominaisuustietoja. Editointi tapahtuu aina työlinkki ja työsolmu -kohteille, eikä editointi vaikuta Maastotietokannasta tulleisiin tielinkkeihin tai niistä luotuihin solmuihin. Myöskään mitään muita VVH-tietokannan tasoja ei voi muokata.

VVH-kantaan vietäväksi toimitetut Suravage- (tai muut) aineistot eivät välttämättä ole ohjeistuksen mukaisesti tehtyjä, jolloin esimerkiksi suunnitelmageometrioiden katkot eivät ole oikeissa paikoissa ja aineistoon muodostuu virheellisiä solmuja. Aineistot voivat jopa mennä VVH:n validoinnista läpi, mutta ne eivät silti vastaa suunnittelijoiden Suravage-ohjeistusta. Tällöin on aineiston käsittelijän vastuulla huomata nämä virheet, ja korjata aineisto VVH:n työkaluilla.

3.4.1 Työlinkkien muokkaus
--------------------------

Pääasiassa editointi noudattaa ArcMapin vakiotyökalujen toimintalogiikkaa. Jos käyttäjällä on jo valmiina jokin taso, joka on tarkoitus kopioida VVH:hon (esim. Suravage-aineistoa), voi sen kopioida työlinkki-tasolle tutulla tavalla Ctrl-C, Ctrl-V.

Jos taas haluaa digitoida käsin uusia viivoja, Digiroad Editor valikon Create Features -painikkeen

![Create Features](k21.png)

kautta voi valita, millaisia kohteita digitoidaan. Koska solmujen luominen on automaattista (kts. seuraava kohta), käyttäjät digitoivat pääasiassa vain viivoja. Valittavina ovat Kävely-/pyörätie ja Muu tieviiva. Valinta tehdään sen mukaan, kumpia ollaan digitoimassa.

Snäppäys on oletuksena päällä, jotta digitointi on helppo kiinnittää muihin työlinkkeihin ja MTK-tielinkkeihin (työlinkkien snäppäys MTK:n linkkeihin ei kuitenkaan katkaise tai muutoin vaikuta MTK-tason tielinkkeihin).

Käyttäjällä on mahdollisuus editoida työlinkki-tason kohteita myös niiden lisäyksen jälkeen. Käytössä ovat kaikki ArcMapin vakioeditointimahdollisuudet, esimerkiksi työlinkin katkaisu ja verteksien lisäys/poisto ja linkkien muokkaus raahaamalla verteksejä. Jos käyttäjä digitoi uuden viivan työalueen rajojen yli, laajenee työalue automaattisesti vastaamaan digitoidun viivan määrittelemiä rajoja.

Solmujen lisäyksestä ja poistamisesta ei tarvitse huolehtia, vaan ne lisätään jälkikäteen seuraavan kohdan mukaan.

3.4.2 Työsolmujen poisto ja lisäys
--------------------------

Kun työlinkkien editointi on valmis, voi kaikki solmut poistaa ja tämän jälkeen lisätä uudelleen, jolloin sovellus luo solmujen tyypit ja linkkien ja solmujen väliset relaatiot automaattisesti. Solmujen poisto ja lisäys tehdään Feature Validation -ikkunassa Tools-välilehdellä. 

Editointi tulee olla edelleen päällä, kun solmut poistetaan ja lisätään.

Solmut poistetaan painikkeesta Remove All. Tämä vie hetken (ei indikaattoria etenemiselle), jonka jälkeen sovellus ilmoittaa poistettujen solmujen lukumäärän. Uudet solmut luodaan painikkeesta Create. Tämä vie hetken, jonka jälkeen sovellus ilmoittaa luotujen solmujen lukumäärän.

![Create Features](k22.png)

_Solmujen poisto ja lisäys -työkalu Feature Validation -> Tools-välilehti._

Kun sovellus luo uusia solmuja, se luo tarvittaessa linkkikatkoja niihin kohtiin, joissa tielinkit on snäpätty risteykseksi, mutta linkkikatko puuttuu. Tämän huomaa kartalla linkkien “välähdyksinä”.

![Solmut](k23.png)

_Vasemmalla tielinkit ennen solmujen luomista, oikealla solmujen luomisen jälkeen. VVH-sovellus tekee solmuja luodessa linkkikatkot automaattisesti tarvittaessa._

Kun solmut on luotu, tehdään tavalliseen tapaan validointi tai jatketaan työskentelyä.

![Solmut](k24.png)

_Työlinkki ja työsolmu -tasojen kohteita. Kohteet on kopioitu VVH-ylläpitäjälle toimitetusta keskilinjasta. Pinkit solmut ovat pseudosolmuja ja mustat solmut ovat risteyssolmuja._

###Työlinkkien digitointi, kun Disable topology checks -valinta ei ole päällä###

Editointi toimii eri tavalla silloin, kun Digiroad: Optionsissa ei ole ruksia kohdassa “Disable topology checks”. Kun käyttäjä digitoi kohteita topologiatarkistusten ollessa päällä, järjestelmä luo solmut automaattisesti ja tarvittaessa katkoo työlinkkejä. Myös risteyksissä sovellus huolehtii automaattisesti linkkien katkomisesta. Viivat tulee snäpätä toisiinsa, jotta katkominen toimii.

Useimmiten työskentely on helpompaa ja sujuvampaa ilman VVH:n automaattisia topologiatarkistuksia. Mahdolliset virheelliset kohteet tulevat kuitenkin ilmi validoinnissa, esimerkiksi jos solmujen relaatiot työlinkkeihin ovat puutteelliset.

![Disable](k25.png)

_Disable topology checks ei ole valittuna._

3.4.3 Työlinkkien ominaisuustiedot
--------------------------

Työlinkin ominaisuustietoja voi muokata tavalliseen tapaan ominaisuustietotaulussa. Useita kohteita voi päivittää Field Calculatorin avulla antamalla arvoksi ko. koodiarvo. Ominaisuustietoja voi muokata myös valitsemalla Digiroad Editor valikosta Attributes

![Attributes](k26.png)

Ominaisuustietojen muokkaus aukeaa sovelluksen oikeaan laitaan kartalta valitulle kohteelle.

![Attributes](k27.png)

_Suunnitelma-aineiston ominaisuustietoja._

Ominaisuustietoja on erilaisia. Koodiarvollisilla ominaisuustiedoilla on VVH:ssa myös domain-arvo, jolloin domain-arvo näkyy käyttöliittymässä. Esimerkiksi Aineistolähde 7 on “Väylä”.

Osa ominaisuustiedoista on pakollisia. Tällöin myös kohteet, joista pakolliset tiedot puuttuvat, jäävät kiinni validoinnissa (mikäli pakollisten tietojen validointi on valittuna, validoinnista tarkemmin myöhemmissä kappaleissa).

VVH-ylläpitäjän ohjeesta voi tarkistaa, mitkä tiedot Suravage-aineistolle tulee täydentää. Kaikkia ominaisuustietoja ei täydennetä Suravage-aineistolle. Ominaisuustietojen koodiarvoluettelot löytyvät tämän käyttöohjeen toiselta välilehdeltä (yläreunasta painike VVH koodiarvoluettelot).

__Pakollisia ominaisuustietoja ovat__ (suluissa tietokannan kentän nimi, joka on tarpeen tietää esim. select by attributes kyselyssä):


|Ominaisuustieto|Tyyppi|Lisätiedot|Oletusarvo|
|---------------|------|---------------|----------|
|Objectid (OBJECTID)|Kokonaisluku|Luodaan automaattisesti||
|Digiroad-tunniste (DRID)|Kokonaisluku|Luodaan automaattisesti||
|Linkkitunniste (LINKID)|Kokonaisluku|Luodaan automaattisesti||
|Työn tunniste (JOBID)|Kokonaisluku|Luodaan automaattisesti||
|Aineistolähde (SOURCEINFO)|Koodiarvo|Valitaan alasvetovalikosta tai annetaan koodiarvo|null|
|Hallinnollinen luokka (ADMINCLASS)|Koodiarvo|Valitaan alasvetovalikosta tai annetaan koodiarvo|null|
|Kohderyhmä (MTKGROUP)|Koodiarvo|Valitaan alasvetovalikosta tai annetaan koodiarvo|Suunnitelmatiestö (viiva)|
|Valmiusaste (CONSTRUCTIONTYPE)|Koodiarvo|Valitaan alasvetovalikosta, esim. rakenteilla tai annetaan koodiarvo|Suunnitteilla|
|Yksisuuntaisuus (DIRECTIONTYPE)|Koodiarvo|Valitaan alasvetovalikosta tai annetaan koodiarvo|null|
|Tasosijainti (VERTICALLEVEL)|Koodiarvo|Valitaan alasvetovalikosta, esim. Tunnelissa, kts. luku 4, tai annetaan koodiarvo|null|
|Kuntatunnus (MUNICIPALITYCODE)|Koodiarvo|Koodiarvo, täydennetään automaattisesti*||
|Hankkeen arvioitu valmistuminen (ESTIMATED_COMPLETION)|Päivämäärä (pp.kk.vvvv)|Jos tieto syötetään Field Calculatorin kautta, tulee tieto syöttää muodossa # kk-pp-vvvv # eli esim. # 10-01-2017 # tarkoittaa 1.10.2017|null|

*) Kuntarajan ylittävän kohteen kuntanumeroa VVH ei täydennä, vaan käyttäjän on täydennettävä se itse. Kuntakoodiksi tulee se kunta, jonka puolella on pidempi osuus tielinkistä.

Muut ominaisuustiedot:

|Ominaisuustieto|Tyyppi|Lisätiedot|Oletusarvo|
|---------------|------|---------------|----------|
|MTK-tunniste (MTKID)|Kokonaisluku|MML:n käyttämä ID, ei täydennetä työlinkille|null|
|Kohdeluokka (MTKCLASS)|Koodiarvo|Valitaan alasvetovalikosta tai annetaan koodiarvo. Vastaa MML:n kohdeluokkaa. VVH:ssa annetaan vain arvoja null ja 12314 Kävely- ja/tai pyörätie|null|
|Tienumero (ROADNUMBER)|Kokonaisluku||null|
|Tieosanumero (ROADPARTNUMBER)|Kokonaisluku||null|
|Ajoratakoodi (TRACK_CODE)|Kokonaisluku||null|
|Päällystetieto (SURFACETYPE)|Koodiarvo|Valitaan alasvetovalikosta tai annetaan koodiarvo|null|
|Sijaintitarkkuus (HORIZONTALACCURACY)|Koodiarvo|Valitaan alasvetovalikosta, esim. 0,5 m tai annetaan koodiarvo|null|
|Korkeustarkkuus (VERTICALACCURACY)|Koodiarvo|Valitaan alasvetovalikosta, esim. 0,5 m tai annetaan koodiarvo|null|
|Kulkutapa (VECTORTYPE)|Koodiarvo|Valitaan alasvetovalikosta, esim. Murto tai annetaan koodiarvo|Murto|
|Pituus (GEOMETRYLENGTH)|Desimaaliluku|Lasketaan automaattisesti, digitoidun viivan pituus (3D pituus=mahdolliset Z-arvot otetaan huomioon)||
|Osoitenumero, vasen, alku (FROM_LEFT)|Kokonaisluku||null|
|Osoitenumero, vasen, loppu (TO_LEFT)|Kokonaisluku||null|
|Osoitenumero, oikea, alku (FROM_RIGHT)|Kokonaisluku||null|
|Osoitenumero, oikea, loppu (TO_RIGHT)|Kokonaisluku||null|
|Voimassaolo, alku (VALID_FROM)|Päivämäärä|Valitaan kalenterivalikosta|null|
|Voimassaolo, loppu (VALID_TO)|Päivämäärä|Valitaan kalenterivalikosta|null|
|Perustuspäivä (CREATED_DATE)|Päivämäärä|Täydennetään automaattisesti, kohteen luontipäivä||
|Perustaja (CREATED_BY)|Tekstikenttä|Täydennetään automaattisesti, digitoijan käyttäjätunnus||
|Validointistatus (VALIDATIONSTATUS)|Koodiarvo|Käsitellään automaattisesti||
|Kohteen olotila (OBJECTSATUS)|Koodiarvo|Käsitellään automaattisesti||
|Tien nimi (suomi) (ROADNAME_FI)|Tekstikenttä||null|
|Tien nimi (ruotsi) (ROADNAME_SE)|Tekstikenttä||null|
|Tien nimi (saame) (ROADNAME_SM)|Tekstikenttä||null|
|MTKHEREFLIP|Koodiarvo|Täydennetään automaattisesti, checkin kääntää geometrian digitointisuunnan tarvittaessa, jotta se vastaa ns. Here-flip -sääntöä. Kenttä tulee mukaan vasta checkin-vaiheessa||
|CUST_CLASS|Tekstikenttä|Käytössä Tampereen täydentävillä geometrioilla, ei Suravagessa||
|CUST_ID_STR|Tekstikenttä|Käytössä Tampereen täydentävillä geometrioilla, ei Suravagessa||
|CUST_ID_NUM|Kokonaisluku|Käytössä Tampereen täydentävillä geometrioilla, ei Suravagessa||
|CUST_OWNER|Koodiarvo|Käytössä Tampereen täydentävillä geometrioilla, ei Suravagessa||

3.4.5 Työsolmun ominaisuustiedot
--------------------------

Solmujen ominaisuustietoja ei tarvitse täydentää, sillä VVH-järjestelmä huolehtii niistä automaattisesti. Poikkeuksena tasan kuntarajalla sijaitsevat työsolmut, joille annettaan kuntanumeroksi jomman kumman kunnan kuntanumero.

3.4.6 Editor trackin seuraaminen editoinnin aikana
--------------------------

Editoinnissa tehtyjä muutoksia tietokantaan voi seurata Editor trackin kautta. Editor track kertoo järjestelmässä editoinnin aikana tapahtuvat (myös automaattiset) toiminnot. Myös kielletyt operaatiot lokittuvat Editor trackiin, jolloin käyttäjä näkee, miksei esimerkiksi kartalle digitoitua viivaa luotu. Editor track avataan Digiroad Editor -> Feature Validation -> Editor track välilehti.

Editor track kirjoittaa lokiin oletuksena vain kriittiset viestit, eli esimerkiksi virheelliset geometriat (vasemman puoleinen kuva alla). Tätä asetusta voi muuttaa Digiroad: Optionsista (Digiroad Tools -työkaluista) tai klikkaamalla hiiren oikeaa nappia Editor Trackissä. Vaihtoehtoisia lokitustasoja on kolme, joista oletuksena on valittuna taso, jossa virheellisten kohteiden digitointi lokitetaan käyttäjälle. Keskitasolla Editor track kirjoittaa lokiin joitakin tapahtumia (linkki luotu, solmu luotu) tai tarkimmalla tasolla kaikki tapahtumat (kohteiden luominen, ominaisuustietojen tallentuminen, kohteiden väliset relaatiot jne., oikeanpuoleinen kuva alla). Editor trackistä voi siis kätevästi seurata, mitä sovelluksessa tapahtuu oman työskentelyn aikana. Eri tasoiset viestit lokitetaan eri väreillä.

![Editor track](k28.png)

_Editor track. Vasemmalla minimilokitus, oikealla tarkin mahdollinen lokitus._

3.5 Validointi: Feature Validation
--------------------------

Käyttäjä voi validoida tekemiään muutoksia sekä työskentelyn aikana että sen jälkeen. Käyttäjän on pakollista validoida tehdyt geometriat ennen niiden vientiä tietokantaan (Digiroad: Checkin) primääritasolle. Validoinnin avulla varmistutaan aineiston topologisesta eheydestä.

3.5.1 Validation rules
--------------------------

Validation rules -välilehdeltä käyttäjä voi valita, mitkä kohteet “jäävät kiinni” validoinnissa. On siis mahdollista tapauskohtaisesti sallia myös sellaisten kohteiden validoinnin läpäisy, jotka eivät välttämättä ole topologisesti tai ominaisuustiedoiltaan “täydellisiä”. Validointeja voi kuitenkin työskentelyn aikana “ajaa” niin monta kertaa kuin tarpeen, ja kohteet jäävät kiinni validoinnissa ruksittujen sääntöjen mukaisesti. Tärkeää (ja pakollista) on ajaa kokonaisvaltainen validointi silloin, kun on aikeissa tehdä checkinin.

![Validointi](k29.png)

_Validointi. Validointisääntöjä voi vaihdella ruksimalla niitä päälle ja pois Feature Validation -ikkunassa._

3.5.2 Validoinnin ajaminen
--------------------------

Editointi on lopetettava ja muutokset tallennettava ennen validointia. Tämän jälkeen Feature Validation -ikkunassa Run-painikkeen painaminen käynnistää validoinnin. Kaikki työhön kuuluvat työlinkit ja työsolmut validoidaan. Validoinnin etenemistä voi seurata validointi-ikkunan alareunan palkista. Virheelliset kohteet lokitetaan välilehdelle Not Valid Features. Validation Rules -ikkunassa ruksitut kohdat vaikuttavat siihen, millaiset kohteet katsotaan virheellisiksi validoinnissa.

![Validointi](k30.png)

_Validoinnin etenemistä voi seurata validointi-ikkunan alareunasta._

Kun validointi on valmis, sovellus ilmoittaa samassa kohdassa, että validointi on valmis. Virheelliset kohteet voi tarkistaa Error List -välilehdeltä.

![Validointi](k31.png)

_Validoinnin tulokset, jos aineistossa on ollut virheellisiä kohteita (vanha kuva - välilehden nimi on nykyisin Error List)._

Jos validoinnin ajaa tämän jälkeen uudelleen siten, että muuttaa validointisääntöjä, poistuvat kohteet korjauslistalta. Esimerkiksi pakollisten ominaisuustietojen poistaminen säännöistä edellisessä tapauksessa lyhentää listaa huomattavasti:

![Validointi](k32.png)

_Validoinnin tulokset, kun osa kohteista on korjattu._

Kohteiden tallentamista tietokantaan primääritasolle eli Checkiniä ei voi tehdä ilman, että virheelliseksi katsotut kohteet on korjattu. Tuplaklikkaamalla kohdetta virhelistalla, sovellus zoomaa ko. kohteeseen. Kun kohteet on korjattu, ajetaan validointi uudelleen ja tarkistetaan, että virhelista on tyhjä.

Pakolliset ominaisuustiedot voi korjata myös valitsemalla listalta hiiren oikealla korjattava kohde ja Fix null values. Tällä tavalla korjausten tekeminen ei edellytä editointitilaan siirtymistä. Kohteet voi korjata yksi kerrallaan tai kaikki kerrallaan. Esim. aineistolähde on usein kaikille kohteille sama samalla työalueella.

![Fix](k33.png)

_Fix Null Values. Pakollisia ominaisuustietoja voi korjata myös Fix Null Values -toiminnolla._

Jos virheellisen kohteen haluaa hyväksyä validointivirheestä huolimatta, voi kohteelle asettaa “Set Valid Flag” -valinnan, jolloin se katsotaan käyttäjän hyväksymäksi kohteeksi ja kohteen voi viedä tietokantaan (seuraava kohta, Digiroad: Checkin).

3.6 Aineiston sisäänmerkkaus: Digiroad: Checkin
--------------------------

Sisäänmerkkaamisen avulla kopioidaan työhön liittyvä muokattu aineisto tietokantaan primääritasolle eli suunnitelmalinkki-tasolle ja suunnitelmasolmu-tasolle. Suunnitelmalinkki-tasolta kohteet siirtyvät jatkossa esimerkiksi OTH-sovelluksen ja Viite-sovelluksen käyttöön. Ilman Checkiniä aineistoa ei siis voi käyttää missään VVH:n rajapintoja hyödyntävissä järjestelmissä.

Jotta Checkin voidaan tehdä, tulee validointi olla kokonaisuudessaan mennyt läpi, eikä työssä saa olla kohteita, jotka eivät ole läpäisseet validointia. Checkin ajetaan Digiroad Editor valikosta valitsemalla Digiroad: Checkin. Jos virheellisiä kohteita on, huomauttaa sovellus tästä. Samoin jos validointi on tekemättä, ei checkiniä voi tehdä, koska kaikki kohteet katsotaan virheellisiksi.

![Checkin](k34.png)

_Checkin, jos validointi on tekemättä tai validoinnissa on jäänyt virheellisiä kohteita._

Kun validointi on tehty onnistuneesti, aktivoituu Checkin painike ja sisäänmerkkauksen voi ajaa.

![Checkin](k35.png)

_Checkin, kun validointi on tehty onnistuneesti._

Kun checkin on tehty, kohteet poistuvat työlinkki ja työsolmu -tasoilta ja siirtyvät suunnitelmalinkki ja suunnitelmasolmu -tasoille. Ne saa palautettua takaisin tekemällä aineistolle taas checkoutin.

Checkin kannattaa tehdä kuitenkin vasta, kuin aineisto on hyödyntämiskelpoisessa vaiheessa. Jos työ ei ole valmis, vaan se halutaan keskeyttää esim. kotiin lähdettäessä, se tehdään Digiroad: Supend Job -valinnalla. Suspend Job on riittävä, jos työ jää täysin kesken (kts. luku 3.7).

![Checkin](k36.png)

_Työlinkit ja työsolmut ennen checkiniä, kohteet valmiita._

![Checkin](k37.png)

_Kohteet suunnitelmalinkki ja suunnitelmasolmu -tasoilla checkinin jälkeen._

###Ns. Here-flip checkinin yhteydessä###

Here-flip -sääntö tarkoittaa, että kaikkien kohteiden digitointisuunnat noudattavat seuraavia sääntöjä:

1. Linkin alkupiste on aina sen eteläisempi piste
1. Täysin itä-länsisuuntaisella linkillä alkupiste on läntinen alkupiste

Käyttäjän ei tarvitse huolehtia itse here-flip -säännön toteutumisesta.

VVH-sovellus tarkistaa checkinin yhteydessä, että tielinkkien digitointisuunnat vastaavat Here-flip -sääntöä. Tarvittaessa digitointisuunta käännetään (etelästä pohjoiseen tai lännestä itään), ja MTKHEREFLIP -kentän arvoksi tulee 1. Samalla digitointisuuntariippuvaiset ominaisuustiedot käännetään (yksisuuntaisuus, osoitenumerot).

Here-flip -säännön toteutumisen voi todentaa checkinin jälkeen lisäämällä suunnitelmalinkit kartalle ja tämän jälkeen Feature Drawing -työkalulla digitointisuuntanuolen piirtoon. Kaikkien nuolien tulisi osoittaa etelästä pohjoiseen tai lännestä itään.

![Hereflip](k38.png)

_Suunnitelmalinkkien digitointisuunnat ovat here-flip -säännön mukaisia._


3.7 Töiden ja työjonon hallinta: Digiroad: Suspend Job ja Digiroad: Work Queue
--------------------------

VVH-sovelluksessa on mahdollista hallita töitä keskeyttämällä niitä, avaamalla uudelleen sekä muokkaamalla olemassa olevien töiden tietoja. Tarvittaessa työn voi myös poistaa.

3.7.1 Työn keskeyttäminen: Digiroad: Suspend Job
--------------------------

Työn keskeyttäminen on normaalitilanne esimerkiksi silloin, kun ei työpäivän aikana saa työtä valmiiksi, ArcMap suljetaan ja työ jää odottamaan hetkeä, jolloin sitä taas jatketaan.  Työ keskeytetään valitsemalla Digiroad Editor valikosta Suspend Job. Jotta työn voi keskeyttää, tulee editointi olla pois päältä ja muutokset tallennettuna. Keskeytyksen jälkeen voi ArcMapin sulkea turvallisesti ja palata työhön myöhemmin.

![Suspend](k39.png)

_Digiroad: Suspend Job_

![Suspend](k40.png)

_ArcMapiä ei voi sulkea, jos VVH:ssa on työ auki._

Työn uudelleen avaaminen ja  jatkaminen myöhemmin onnistuu työjonon kautta.

3.7.2 Työjonon hallinta: Digiroad: Work Queue
--------------------------

Keskeneräisiä töitä voidaan hallita työjonossa.  Mm. töiden avaaminen uudelleen (Open Job) keskeytyksen jälkeen ja työn vapautus (Release Job) tapahtuu työjonon kautta. Työjono avataan Digiroad Editor -valikosta valitsemalla Digiroad: Work Queue. Työjono näyttää kaikki julkiset työt. On tärkeää, ettei töitä turhaan tehdä yksityisiksi, jotta ne näkyvät ja ovat hallittavissa työjonossa kaikkien toimesta.

![Jono](k41.png)

_Työjono._

Klikkamalla työtä työjonosta, nähdään työn tiedot. Eri välilehdillä voi hallita myös työn liitteitä jne. aivan kuten silloin, kun työ rekisteröitiin. Työn tietoja voi muokata vain silloin, kun se ei ole auki. Jos siis haluaa lisätä työhön uuden liitteen tai layerin, tulee se ensin sulkea ja sitten muokata sitä työjonosta.

Julkisten töiden hallinta ja niiden avaaminen on sallittua kaikille käyttäjille. Esimerkiksi kohtaan Notes voi myös toiset kirjoittaa huomiota työstä, jos on jatkanut toisen aloittamaa työtä.

3.7.3 Työn avaaminen (Open Job)
--------------------------

Työ avataan uudelleen valitsemalla Open Job. Tämän jälkeen voi editointia jatkaa taas tavallisesti. Checkout tehdään tarvittaessa. Sovellus ei anna aloittaa editointia, jos checkout on tekemättä.

Valmiit ja yksityiset työt saadaan näkyviin valitsemalla Work Queue -alasvetovalikosta Show Private Queue ja Show Completed Queue. Valmiita töitä ei voida avata uudelleen ja yksityisiä töitä voi hallita vain niiden omistaja sekä admin-käyttäjä (kts. admin-käyttäjän oikeudet työjonon hallinnassa).

![Jono](k42.png)

_Valmiit ja yksityiset työt._

3.7.4 Työn merkkaaminen valmiiksi (Complete Job)
--------------------------

Kun Checkin on tehty ja työ on kokonaan valmis (siihen ei ole tarpeen palata), voi työn merkata valmiiksi Digiroad Editor -valikosta valitsemalla Digiroad: Complete Job.

Huom! Valmiiksi merkattua työtä ei voi avata enää uudelleen käyttöliittymästä eikä sitä pääse siis enää editoimaan.

Jos kuitenkin käyttäjällä on tarve korjata työtä Complete Job -valinnan jälkeen, voi VVH:n kehitystiimiä pyytää palauttamaan työ takaisin työjonoon. Kehitystiimille ilmoitetaan työn tarkka nimi (tai työn numero, ei näy listassa). Jos työn nimeä ei muista, sen voi tarkistaa avaamalla työjonosta valmiiden töiden listan.

![Jono](k43.png)

_Valmiiden töiden lista._

3.7.5 Työn vapautus (Release Job),  jos ArcMap kaatuu
--------------------------

Työn vapautus on tarpeen, jos esimerkiksi ArcMap on kaatunut kesken työskentelyn. Tällöin työ jää tilanteeseen, jossa sitä ei voi jatkaa ilman vapautusta. Työ vapautetaan valitsemalla hiiren oikealla työ työjonosta, valitsemalla Manage Jobs -> Release Job. 

3.7.6 Työn poistaminen (Delete Job)
--------------------------

Työ poistetaan samasta paikasta, mistä työ vapautetaan eli valitsemalla hiiren oikealla työ työjonosta, valitsemalla Manage Jobs -> Delete Job. Pääasiassa töitä ei tarvitse poistaa.

Työn poistaminen johtaa kaikkien työlinkkien ja työsolmujen poistamiseen. Kuitenkin ne kohteet, joille Checkin on tehty, eivät poistu vaan ne jäävät tietokantaan, koska ne ovat suunnitelmalinkki ja suunnitelmasolmu -tasoilla. Näiden kohteiden JOBID menee kuitenkin arvolle null, koska ko. työ on poistettu.

3.7.7 Admin-käyttäjän oikeudet työjonon hallinnassa
--------------------------

Jos käyttäjä on admin, voi käyttäjä myös vapauttaa ja poistaa toisten töitä. Lisäksi adminilla on oikeudet siirtää muiden töitä yksityiseksi ja julkiseksi. Admin-oikeuksia voi pyytää VVH:n kehitystiimiltä.

Jos käyttäjälle annetaan admin-oikeudet, tulee käyttäjän kirjautua VVH-järjestelmään uudelleen, jotta oikeudet toimivat.

3.7.8 Päällekkäiset työalueet
--------------------------

VVH:ssa ei ole estettä sille, etteivätkö työalueet voisi olla päällekkäin. Kaksi päällekkäistä työaluetta ei kuitenkaan voi olla yhtäaikaa auki. Jos yrittää avata työaluetta, jonka kanssa toinen työalue on jo päällekkäin ja auki, ei VVH anna avata toista työaluetta. Tällöin tulee odottaa, että toinen työalue suljetaan tai ottaa yhteyttä toisen työalueen käyttäjään ja pyytää sulkemaan työalue.

![Overlap](k54.png)

_Ilmoitus, jos yrittää avata työalueen, jonka kanssa päällekkäinen työalue on jo auki._

3.8 Työalueen päivittäminen
--------------------------

Työalueen koko päivittyy automaattisesti, jos kohteita digitoi alueen reunaviivojen yli. Työalue suurenee 100 m päähän uloimmasta työlinkistä. 

3.8.1 Työalueen suurentaminen
--------------------------

Jos työalueen haluaa päivittää kokonaisuudessaan isommaksi, sen voi tehdä avaamalla ko. työn ja digitoimalla CTRL-nappi pohjassa uuden työalueen Create Job Area -työkalulla

![Area](k45.png)

Uuden työalueen tulee leikata vanhan työalueen raja tai ympäröidä se kokonaan. Tämän jälkeen sovellus varmistaa, halutaanko työaluetta muokata. Pienempää työaluetta ei voi tehdä, koska muuten jo digitoidut kohteet voisivat jäädä sen ulkopuolelle.

Create Job Area -työkalu on käytössä vain silloin, kun editointi ei ole päällä.

3.8.2 Työalueen jakaminen kahteen uuteen työalueeseen (toistaiseksi ei tarpeellinen toiminto)
--------------------------

Olemassa olevan työalueen voi jakaa kahteen uuteen alueeseen. Jaon aikana editointi ei saa olla päällä. Tämä voi olla hyödyllistä esimerkiksi silloin, jos osa työalueesta halutaan jakaa toiselle henkilölle tehtäväksi tai tehtäväksi myöhemmässä vaiheessa. Tämä tehdään Create Job Area -työkalulla digitoimalla uusi alue, joka leikkaa vanhan työalueen tai on kokonaan sen sisällä. Tämän jälkeen avautuu uuden työn rekisteröinti-ikkuna.

Alla on kuvasarja uuden työalueen tekemisestä:

![Area](k44.png)

_Työalueen jakaminen. Vanha työalue ennen jakoa._

![Area](k46.png)

_Työalueen jakaminen. Uuden työalueen digitointi vanhan työalueen sisälle._

Avautuvassa ikkunassa voi valita, siirretäänkö vanhan työn kohteet uuteen työhön: “Move contained data to new job”. Uuteen työhön siirtyvät kaikki kokonaan uuden työalueen sisällä olevat kohteet, ja ne poistuvat vanhasta työstä.

![Area](k47.png)

_Uuden työalueen rekisteröinti._

Klikkaamalla register, sovellus kysyy, halutaanko rekisteröidä uusi työalue tai päivittää vanha.

Jos on valittu Move contained data to new job, vanhasta työalueesta poistuu kohteet uuteen työalueeseen. Jos tätä ei ole valittu, syntyy uusi työalue, mutta ilman vanhan työalueen kohteita.

![Area](k48.png)

_Vanha työalue jakamisen jälkeen, kohteet vanhasta työalueesta on siirretty uuteen._

Rekisteröinnin jälkeen uusi työalue on samanlainen työalue, kuin kaikki muutkin työalueet.

4. Tasosijainnin lisääminen automaattisesti
--------------------------

Väyläverkon hallintaan tuodulle työlinkki-aineistolle on mahdollista lisätä automaattisesti tasosijainti -tieto, jos aineistossa on mukana Z-koordinaatit. Jos Z-koordinaatit puuttuvat, automaattinen tasosijainti antaa kaikille kohteille arvon 0 eli Pinnalla. Tasosijainnit voi määritellä myös manuaalisesti ominaisuustietotauluun, jos Z-koordinaatteja ei ole toimitettu.

Tasosijainteja määrittäessä editointi tulee olla pois päältä. Tasosijainnit lasketaan Z-koordinaateista automaattisesti valitsemalla Feature Validation -ikkunan Tools -välilehdeltä Vertical Level of Links -> Update.

![Vertical](k49.png)

_Vertical Level of Links._

![Vertical](k50.png)

_Kun sovellus on laskenut tasosijainnit, ne on nähtävissä työlinkkien ominaisuustietotaulussa._

5. Keskeneräisen työn alueelle tulleet muutokset MTK-importeissa
--------------------------

SURAVAGE-töiden on tarkoitus sopia nykyiseen MTK-geometriaan esim. linkkikatkojen osalta. Koska VVH-ylläpitäjän on hyvä olla tietoinen keskeneräisen työn alueelle MTK-importeissa tulleista muutoksista, sovellus ilmoittaa tästä, kun työtä avataan. Ilmoitusta ei tule, jos käsiteltävien kohteiden lokissa ei ole kohteita (kts. lokin avaaminen seuraavasta luvusta).

![Loki](k57.PNG)

_Ilmoitus työn alueelle tulleista muutoksista._

Loki-ikkunaan voi painaa ok. Tämän jälkeen avataan loki, ja tarkistetaan onko muutoksilla vaikutuksia työlinkkeihin.

5.1. Lokin avaaminen
--------------------------

Lokin saa näkyviin valikosta Digiroad: Editor -> Digiroad: Job Changes. Loki aukeaa näytön vasempaan laitaan.

![Työn alueen muutosloki.](k58.PNG)

_Työn alueelle tulleiden muutosten loki._

Lokissa olevat sarakkeet kertovat kohteen MTK-ID:n ja MML:n tekemän muutostyypin. MTK-ID:n avulla käyttäjä voi zoomata oikeaan paikkaan tarkistaakseen muutokset.

5.2. Lokin läpikäymiseen tarvittavat tasot
--------------------------

Lokin läpikäymistä varten varsinaisen Työlinkki-tason lisäksi tulee kartalla olla myös taso Tielinkki (MTK), jotta kohteisiin voi zoomata ja niiden vaikutukset työhön (työlinkkeihin) tarkistaa.

5.3. Lokin läpikäyminen
--------------------------

1. Klikataan hiiren oikealla __lokista__ kohdetta ja valitaan "Zoom to Extent", sovellus kohdistaa kyseiseen MTK-ID:seen eli tielinkkiin
1. Tarkistetaan, vaikuttaako ko. tielinkissä tapahtunut muutos työlinkkeihin (kohteet eivät välttämättä osu edes työlinkkeihin, jolloin sen tarkempaa tarkastelua ei tarvita)
1. Kun tarkastelu on tehty, kohteen saa pois lokista painamalla painiketta "Mark Processed"

Jos lokissa on kohde statuksella "Deleted", ei siihen voi kohdistaa, koska se on poistettu. Nämä kohteet löytyvät tarvittaessa tasolta "Linkki-solmu historia", jos niitä haluaa tarkastella.


6. Suunnitelmalinkkien historiointi manuaalisesti
--------------------------

Kun MML palauttaa suunnitelmalinkit VVH:aan MTK-sanomien mukana, vastaava Suravage-aineisto löytyy myös tielinkki-tasolta. Suunnitelmakohteet historioituvat suunnitelmalinkki-tasolta. Jos MML:n toimittama suunnitelmalinkki ei ole täysin identtinen suunnitelmalinkin kanssa geometrialtaan, suunnitelmalinkki ei historioidu automaattisesti, vaan se on tehtävä VVH:ssa VVH-ylläpitäjän toimesta.

Kohteet tulevat manuaaliseen historiointiin, jos

- Kohde on huomattavasti muuttunut MML:lla, esimerkiksi sitä pidennetty, lyhennetty, katkaistu tai yhdistetty toiseen linkkiin
- Kohde on katsottu vastaavan jotain vanhaa MTK-ID:tä, jolloin se ei ole varsinainen uusi Suravage-kohde eikä sitä olisi tarvinnut toimittaa MML:lle Suravage-prosessissa

6.1. Lokin avaaminen
--------------------------

Kullakin käyttäjällä on loki, jossa on lueteltu ne MTK-ID:t, jotka on tunnistetu Suravage-kohteiksi, mutta joiden suunnitelmalinkkiä ei ole voitu historioida automaattisesti. Lokin saa näkyviin valikosta Digiroad: Editor -> Suravage History Log....

Loki aukeaa näytön vasempaan laitaan.

![Suravage manual history log](k56.png)

_Suravage manual history log._

Lokissa olevat sarakkeet kertovat kohteen MTK-ID:n, MML:n tekemän muutostyypin sekä työn nimen, johon suunnitelmalinkki on alunperin tehty.

6.2 Historioinnissa tarvittavat tasot
--------------------------

Historiointia varten kartalle tulee valita Add Layers -valikosta tasot:

- Tielinkki (MTK)
- Suunnitelmalinkki

Tielinkki -tason avulla kohdistetaan lokista kohteeseen, jolle ei ole tunnistettu automaattisesti historioitavaa suunnitelmalinkkiä. Suunnitelmalinkki -tasolta vastaavasti valitaan kohteet, jotka historioidaan.

6.3. Historiointiprosessi
--------------------------

Historiointi tapahtuu seuraavalla tavalla:

1. Klikataan hiiren oikealla __lokista__ kohdetta ja valitaan "Zoom to Extent", sovellus kohdistaa kyseiseen MTK-ID:seen eli tielinkkiin
1. Huomataan suunnilleen samasta kohdasta löytyvä suunnitelmalinkki, joka voidaan historioida, koska MTK-kohde on korvannut sen
1. Valitaan ArcMapin valintatyökalulla historioitava __suunnitelmalinkki__ kartalta (historiointia varten kannattaa ko. taso laittaa asetukselle "Make this only selectable layer")
1. Painetaan lokin alareunasta painiketta "Move to history"
1. Nyt MTK-ID on käsitelty, ja sen saa pois lokista painamalla painiketta "Mark Processed"

Näin käydään läpi koko loki, jotta suunnitelmalinkit eivät jää roikkumaan suunnitelmalinkki-tasolle kun MTK-linkit ovat ne korvanneet.


7. MTK-päivitysten ajaminen ja seuranta (vain kehitystiimin käyttöön)
--------------------------

VVH-sovellus päivittää MTK:n päivityssanomat joka aamuyö automaattisesti. Tarvittaessa päivitykset voi myös laittaa pois päältä (esim. Digiroad-julkaisua varten). Pois päältä olevista päivityksistä tai virheestä päivitysajossa ilmoitetaan kaikille käyttäjille sisäänkirjautumisen yhteydessä. Jos päivitykset ovat päällä, eikä virhetilanteita ole, niin ilmoitusta ei tule.

Päivityksiä ja niiden ajoa hallitsee toistaiseksi VVH:n kehitystiimi, eikä muiden käyttäjien ole syytä puuttua päivitysten ajoon.

7.1. Päivitysten hallinta
--------------------------

Päivitykset voi poistaa automaattiajosta eli disabloida Digiroad Options -valikosta Database välilehdeltä. Kun VVH sovellus avataan, käyttäjälle ilmoitetaan, jos MTK-päivitykset eivät ole ajossa.

Päivitykset palautetaan ajoon palauttamalla ruksi ko. kohtaan. Normaalitilassa olevista päivityksistä ei tule ilmoituksia käyttäjille.

![MTK](k51.png)

_Digiroad Updates. MTK-päivitysten disablointi._

![MTK](k52.png)

_Kirjautumisen yhteydessä näytettävä huomautus päivityksien disabloinnista._

7.2. Digiroad: RoadLink Changes
--------------------------

RoadLink Changes -valikossa voi tarkastella MTK-päivityksiä ja niiden muutoksia VVH:n tielinkki-aineistossa. RoadLink Changes avataan Digiroad Editor -alasvetovalikosta. Kun RoadLink Changes -ikkunan avaa, tulee kartalle myös Tielinkin muutokset.lyr -taso, joka sisältää muutoskohteiden polygonit. Niiden avulla muutoksia voi olla helpompi tarkastella, erityisesti hyödyntämällä attribuuttitaulua ja siellä Select by Attributes -toimintoa esim. tietyn tyyppisten muutosten tarkasteluun (tuntematon, lyhennetty, pidennetty..).

![MTK](k53.png)

_RoadLink Changes. MTK-päivitysten tarkastelu._

List Updates -kohdasta voi valita, miten pitkältä ajalta haluaa historiaa tarkastella. Klikkaamalla ylemmästä paneelista tämän jälkeen päivityspakettia (zip), avautuu alempaan paneeliin sen tiedot linkkien muutoksista. Mitä muutoksia näytetään, voi valita Digiroad Optionsin Database -välilehdeltä tai RoadLink Changes -ikkunan Window -alasvetovalikosta.

Tuplaklikkaamalla muuttunutta kohdetta tai valitsemalla se hiiren oikealla ja Pan to Extent tai Zoom to extent pääsee tarkastelemaan kohdetta kartalla.

Eri muutostyyppejä ovat:

0 - Tuntematon
1 - Yhdistetty (muokattu osa)
2 - Yhdistetty (poistettu osa)
3 - Pidennetty (yhtenevä osa)
4 - Pidennetty (uusi osa)
5 - Jaettu (muokattu osa)
6 - Jaettu (uusi osa)
7 - Lyhennetty (yhtenevä osa)
8 - Lyhennetty (poistettu osa)
9 - Korvattu uudella (pidemmällä)
10 - Korvattu uudella (lyhyemmällä)
11 - Poistettu
12 - Uusi
13 - Korvattu uudella (yhtenevä osa)
14 - Korvattu uudella (uusi osa)
15 - Korvattu uudella (poistettu osa)

Kun tarkastelee päivityshistorian kohteita, kannattaa kartalle ottaa myös tielinkin historia -taso ja visualisoida kaikki historian muutostyypit näkyviin. Tielinkin historiassa näkee vanhat tielinkit sellaisenaan, kuin ne ovat menneet historiaan.

7.3 Here-flip
--------------------------

Maastotietokannan kohteiden digitointisuunnat tarkistetaan MTK-importin yhteydessä. Tarvittaessa digitointisuunta ja digitointisuuntariippuvaiset ominaisuustiedot käännetään Here-flip -säännön mukaisesti. Here-flip -sääntö tarkoittaa, että kaikkien tielinkkien digitointisuunnat noudattavat seuraavia sääntöjä:

1. Linkin alkupiste on aina sen eteläisempi piste
1. Täysin itä-länsisuuntaisella linkillä alkupiste on läntinen alkupiste

Tarvittaessa digitointisuunta käännetään (etelästä pohjoiseen tai lännestä itään), ja MTKHEREFLIP -kentän arvoksi tulee 1. Vastaavasti yksisuuntaisuus tieto käännetään (jos arvo on muuta kuin molempiin suuntiin) ja osoitenumeroiden alku- ja loppuarvot käännetään toisin päin.
