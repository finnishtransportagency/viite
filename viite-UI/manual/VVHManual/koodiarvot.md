VVH koodiarvoluettelo
=========================================

Ominaisuustietotaulussa kentän nimi ja koodiarvot näkyvät selkeämpänä versiona. Field Calculator ja Select by Attributes -työkalut käyttävät kuitenkin tietokannan kenttien nimiä ja koodiarvoja. 

Alla olevaan taulukkoon on kerrottu kenttien nimet molemmilla tavoilla, sekä kunkin kentän koodiarvoluettelot, jos kenttä on koodiarvollinen.


|Ominaisuustieto|Tyyppi|Koodiarvot|
|---------------|------|-----------|
|Objectid (OBJECTID)|Kokonaisluku||
|Digiroad-tunniste (DRID)|Kokonaisluku||
|Linkkitunniste (LINKID)|Kokonaisluku||
|Työn tunniste (JOBID)|Kokonaisluku||
|Aineistolähde (SOURCEINFO)|Koodiarvo|0 Muu<BR>1 Maanmittauslaitos<BR>2 SYKE<BR>3 Merenkulkulaitos<BR>4 Metsähallitus<BR>5 Puolustusvoimat<BR>6 Kunta<BR>7 Liikennevirasto|
|Hallinnollinen luokka (ADMINCLASS)|Koodiarvo|1 Valtio<BR>2 Kunta<BR>3 Yksityinen|
|Kohderyhmä (MTKGROUP)|Koodiarvo|24 Suunnitelmatiestö<BR>25 Tiestö (viiva)<BR>45 Tiestö (piste)<BR>64 Tiestö (alue)|
|Valmiusaste (CONSTRUCTIONTYPE)|Koodiarvo|0 Käytössä<BR>1 Rakenteilla<BR>3 Suunnitteilla|
|Yksisuuntaisuus (DIRECTIONTYPE)|Koodiarvo|0 Kaksisuuntainen<BR>1 Digitointisuunnassa<BR>2 Digitointisuuntaa vastaan|
|Tasosijainti (VERTICALLEVEL)|Koodiarvo|10 Määrittelemätön<BR>0 Pinnalla<BR>-1 Pinnan alla<BR>1 Pinnan yllä taso 1<BR>2 Pinnan yllä taso 2<BR>3 Pinnan yllä taso 3<BR>4 Pinnan yllä taso 4<BR>5 Pinnan yllä taso 5<BR>-11 Tunnelissa|
|Kuntatunnus (MUNICIPALITYCODE)|Koodiarvo|Koodiarvo kuntanumeron mukaan|
|Hankkeen arvioitu valmistuminen (ESTIMATED_COMPLETION)|Päivämäärä (pp.kk.vvvv)||
|MTK-tunniste (MTKID)|Kokonaisluku||
|Kohdeluokka (MTKCLASS)|Koodiarvo|Suravagessa käytössä vain luokka 12314 Kävely- ja/tai pyörätie<BR>|
|Tienumero (ROADNUMBER)|Kokonaisluku||
|Tieosanumero (ROADPARTNUMBER)|Kokonaisluku||
|Ajoratakoodi (TRACK_CODE)|Kokonaisluku|Käytännössä arvo 0, 1 tai 2|
|Päällystetieto (SURFACETYPE)|Koodiarvo|0 Tuntematon<BR>1 Ei päällystettä<BR>2 Kestopäällyste|
|Sijaintitarkkuus (HORIZONTALACCURACY)|Koodiarvo|Ei käytössä Suravagessa<BR>500 0,5 m<BR>800 0,8 m<BR>1000 1 m<BR>10000 10 m<BR>100000 100m<BR>12500 12,5 m<BR>15000 15 m<BR>2000 2 m<BR>20000 20 m<BR>25000 25 m<BR>3000 3 m<BR>30000 30 m<BR>4000 4 m<BR>5000 5 m<BR>7500 7,5 m<BR>8000 8 m<BR>80000 80 m<BR>0 Ei määritelty|
|Korkeustarkkuus (VERTICALACCURACY)|Koodiarvo|Ei käytösssä Suravagessa<BR>500 0,5 m<BR>800 0,8 m<BR>1000 1 m<BR>10000 10 m<BR>100000 100m<BR>12500 12,5 m<BR>15000 15 m<BR>2000 2 m<BR>20000 20 m<BR>25000 25 m<BR>3000 3 m<BR>30000 30 m<BR>4000 4 m<BR>5000 5 m<BR>7500 7,5 m<BR>8000 8 m<BR>80000 80 m<BR>1 Ei määritelty<BR>100001 KM 10 m<BR>201 KM 2 m<BR>250001 KM 25 m|
|Kulkutapa (VECTORTYPE)|Koodiarvo|1 Murto<BR>2 Käyrä|
|Pituus (GEOMETRYLENGTH)|Desimaaliluku||
|Osoitenumero, vasen, alku (FROM_LEFT)|Kokonaisluku||
|Osoitenumero, vasen, loppu (TO_LEFT)|Kokonaisluku||
|Osoitenumero, oikea, alku (FROM_RIGHT)|Kokonaisluku||
|Osoitenumero, oikea, loppu (TO_RIGHT)|Kokonaisluku||
|Voimassaolo, alku (VALID_FROM)|Päivämäärä||
|Voimassaolo, loppu (VALID_TO)|Päivämäärä||
|Perustuspäivä (CREATED_DATE)|Päivämäärä||
|Perustaja (CREATED_BY)|Tekstikenttä||
|Validointistatus (VALIDATIONSTATUS)|Koodiarvo|0 Ei tarkastettu<BR>1 Virheellinen<BR>2 Hyväksytty<BR>3 Käyttäjän hyväksymä|
|Kohteen olotila (OBJECTSATUS)|Koodiarvo|1 Alkuperäinen<BR>2 Muokattu ominaisuuksia<BR>3 Muokattu<BR>4 Uusi<BR>8 Merkattu poistettavaksi<BR>9 Poistettu|
|Tien nimi (suomi) (ROADNAME_FI)|Tekstikenttä||
|Tien nimi (ruotsi) (ROADNAME_SE)|Tekstikenttä||
|Tien nimi (saame) (ROADNAME_SM)|Tekstikenttä||
|MTKHEREFLIP|Koodiarvo|0 Ei käännetty<BR>1 Käännetty|
|CUST_CLASS|Tekstikenttä||
|CUST_ID_STR|Tekstikenttä||
|CUST_ID_NUM|Kokonaisluku||
|CUST_OWNER|Koodiarvo|Kuntanumero geometrian omistavan kunnan mukaan|

![Koodiarvot](k55.png)

_Hallinnollinen luokka ominaisuustietotaulussa, Field Calculatorissa ja Select by Attributes -työkalussa._
