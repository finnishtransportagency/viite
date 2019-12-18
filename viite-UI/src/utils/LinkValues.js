(function (root) {
    root.LinkStatus = {
        NotHandled:                 {value: 0,  description: "NotHandled", transitionFrom: [0]},
        Unchanged:                  {value: 1,  description: "Unchanged",  transitionFrom: [0, 1, 3, 5]},
        New:                        {value: 2,  description: "New",        transitionFrom: [99, 2]},
        Transfer:                   {value: 3,  description: "Transfer",   transitionFrom: [0, 1, 3, 5]},
        Numbering:                  {value: 4,  description: "Numbering",  transitionFrom: [0, 1, 3, 4, 5]},
        Terminated:                 {value: 5,  description: "Terminated", transitionFrom: [0, 1, 3, 5]},
        Revert:                     {value: 6,  description: "Revert",     transitionFrom: [1, 2, 3, 4, 5, 6]},
        Undefined:                  {value: 99, description: "?",          transitionFrom: []}
    };

    root.Anomaly = {
        None:                       {value: 0, description: "None"},
        NoAddressGiven:             {value: 1, description: "NoAddressGiven"},
        GeometryChanged:            {value: 2, description: "GeometryChanged"},
        Illogical:                  {value: 3, description: "Illogical"}
    };

    root.LinkGeomSource = {
        NormalLinkInterface:        {value: 1,  descriptionFI: "MML",            description: "NormalLinkInterface"},
        ComplementaryLinkInterface: {value: 2,  descriptionFI: "Täydentävä",     description: "ComplementaryLinkInterface"},
        SuravageLinkInterface:      {value: 3,  descriptionFI: "Suravage",       description: "SuravageLinkInterface"},
        FrozenLinkInterface:        {value: 4,  descriptionFI: "MML jäädytetty", description: "FrozenLinkInterface"},
        HistoryLinkInterface:       {value: 5,  descriptionFI: "MML historia",   description: "HistoryLinkInterface"},
        Unknown:                    {value: 99, descriptionFI: "Tuntematon",     description: "Unknown"}
    };

    root.ConstructionType = {
        InUse:                      {value: 0, description: "InUse"},
        UnderConstruction:          {value: 1, description: "UnderConstruction"},
        Planned:                    {value: 3, description: "Planned"},
        UnknownConstructionType:    {value: 99, description: "UnknownConstructionType"}
    };

    root.SelectionType = {
        All:                        {value: 0, description: "all"},
        Floating:                   {value: 1, description: "floating"},
        Unknown:                    {value: 99, description: "unknown"}
    };

    root.RoadClass = {
        HighwayClass:               {value: 1, description: "HighwayClass"},
        MainRoadClass:              {value: 2, description: "MainRoadClass"},
        RegionalClass:              {value: 3, description: "RegionalClass"},
        ConnectingClass:            {value: 4, description: "ConnectingClass"},
        MinorConnectingClass:       {value: 5, description: "MinorConnectingClass"},
        StreetClass:                {value: 6, description: "StreetClass"},
        RampsAndRoundAboutsClass:   {value: 7, description: "RampsAndRoundAboutsClass"},
        PedestrianAndBicyclesClass: {value: 8, description: "PedestrianAndBicyclesClass"},
        WinterRoadsClass:           {value: 9, description: "WinterRoadsClass"},
        PathsClass:                 {value: 10, description: "PathsClass"},
        PrivateRoadClass:           {value: 12, description: "PrivateRoadClass"},
        NoClass:                    {value: 99, description: "NoClass"}
    };

    root.TrafficDirection = {
        BothDirections:             {value: 2, description: "Molempiin suuntiin"},
        AgainstDigitizing:          {value: 3, description: "Digitointisuuntaa vastaan"},
        TowardsDigitizing:          {value: 4, description: "Digitointisuuntaan"},
        UnknownDirection:           {value: 99, description: "Tuntemattomaan suuntaan"}
    };

    root.SideCode = {
        BothDirections:             {value: 1, description: "BothDirections"},
        TowardsDigitizing:          {value: 2, description: "TowardsDigitizing"},
        AgainstDigitizing:          {value: 3, description: "AgainstDigitizing"},
        Unknown:                    {value: 99, description: "Unknown"}
    };

    root.CalibrationCode = {
        None:                       {value: 0, description: "None"},
        AtEnd:                      {value: 1, description: "AtEnd"},
        AtBeginning:                {value: 2, description: "AtBeginning"},
        AtBoth:                     {value: 3, description: "AtBoth"}
    };

    root.ProjectStatus = {
        Closed:                     {value: 0, description: "Suljettu"},
        Incomplete:                 {value: 1, description: "Keskeneräinen"},
        Sent2TR:                    {value: 2, description: "Lähetetty tierekisteriin"},
        ErrorInTR:                  {value: 3, description: "Virhe tierekisterissä"},
        TRProcessing:               {value: 4, description: "Tierekisterissä käsittelyssä"},
        Saved2TR:                   {value: 5, description: "Viety tierekisteriin"},
        Failed2GenerateTRIdInViite: {value: 6, description: "Tierekisteri ID:tä ei voitu muodostaa"},
        Deleted:                    {value: 7, description: "Poistettu projekti"},
        ErrorInViite:               {value: 8, description: "Virhe Viite-sovelluksessa"},
        SendingToTR:                {value: 9, description: "Lähettää Tierekisteriin"},
        Unknown:                    {value: 99, description: "Tuntematon"}
    };

    root.ProjectStatusToDisplay = [root.ProjectStatus.Incomplete.value, root.ProjectStatus.Sent2TR.value, root.ProjectStatus.ErrorInTR.value,
        root.ProjectStatus.TRProcessing.value, root.ProjectStatus.ErrorInViite.value, root.ProjectStatus.SendingToTR.value];

    root.Track = {
        Combined:                   {value: 0, description: "Combined"},
        RightSide:                  {value: 1, description: "RightSide"},
        LeftSide:                   {value: 2, description: "LeftSide"},
        Unknown:                    {value: 99, description: "Unknown"}
    };

    root.RoadZIndex = {
        VectorLayer:                {value: 1},
        AnomalousMarkerLayer:       {value: 2},
        CalibrationPointLayer:      {value: 3},
        UnderConstructionLayer:     {value: 4},
        GeometryChangedLayer:       {value: 5},
        ReservedRoadLayer:          {value: 6},
        HistoricRoadLayer:          {value: 7},
        DirectionMarkerLayer:       {value: 8},
        GreenLayer:                 {value: 10},
        unAddressedRoadsLayer:      {value: 11},
        IndicatorLayer:             {value: 99}
    };

    root.RoadType = {
        Empty:                          {value:0, description:"", displayText:"--"},
        PublicRoad:                     {value:1, description:"Yleinen tie", displayText:"1 Maantie"},
        FerryRoad:                      {value:2, description:"Lauttaväylä yleisellä tiellä", displayText:"2 Lauttaväylä maantiellä"},
        MunicipalityStreetRoad:         {value:3, description:"Kunnan katuosuus", displayText:"3 Kunnan katuosuus"},
        PublicUnderConstructionRoad:    {value:4, description:"Yleisen tien työmaa", displayText:"4 Maantien työmaa"},
        PrivateRoadType:                {value:5, description:"Yksityistie", displayText:"5 Yksityistie"},
        UnknownOwnerRoad:               {value:9, description:"Omistaja selvittämättä", displayText:"9 Omistaja selvittämättä"},
        Unknown:                        {value:99, description:"Ei määritelty", displayText:""}

    };

    root.RoadTypeShort = {
        PublicRoad:                     {value:1, description:"tie"},
        FerryRoad:                      {value:2, description:"lautta"},
        MunicipalityStreetRoad:         {value:3, description:"katu"},
        PublicUnderConstructionRoad:    {value:4, description:"työmaa"},
        PrivateRoadType:                {value:5, description:"yks"},
        UnknownOwnerRoad:               {value:9, description:"omist=?"}
    };

    root.RoadLinkType = {
        UnknownRoadLinkType:        {value: 0, description: "UnknownRoadLinkType"},
        NormalRoadLinkType:         {value: 1, description: "NormalRoadLinkType"},
        ComplementaryRoadLinkType:  {value: 3, description: "ComplementaryRoadLinkType"},
        FloatingRoadLinkType:       {value: -1, description: "FloatingRoadLinkType"},
        SuravageRoadLink:           {value: 4, description: "SuravageRoadLink"}
    };

    root.RoadNameSource = {
        UnknownSource:              {value: 99, description: "Unknown Source"},
        ProjectLinkSource:          {value: 0, description: "Project Link Source"},
        RoadAddressSource:          {value: 1, description: "Road Name Source"}
    };

    root.ProjectError = {
        TerminationContinuity:  {value: 18},
        DoubleEndOfRoad:        {value: 19},
        RoadNotReserved:        {value: 27}
    };

    /*
    The meta key codes are browser dependant, in proper:
        Firefox: 224
        Opera: 17
        WebKit (Safari/Chrome): 91 (Left Apple) or 93 (Right Apple)

     A blessing in disguise, CTRL key code is always fixed to 17.
     */
    root.MetaKeyCodes = [91, 93, 224, 17];

    root.SelectKeyName = "ContextMenu";

    root.UnknownRoadId = 0;

    root.NewRoadId = -1000;

    root.BlackUnderlineRoadTypes = [root.RoadType.MunicipalityStreetRoad.value, root.RoadType.PrivateRoadType.value];

    root.ElyCodes = {
        ELY_U:      {value: 1, name: "Uusimaa"},
        ELY_T:      {value: 2, name: "Varsinais-Suomi"},
        ELY_KAS:    {value: 3, name: "Kaakkois-Suomi"},
        ELY_H:      {value: 4, name: "Pirkanmaa"},
        ELY_SK:     {value: 8, name: "Pohjois-Savo"},
        ELY_KES:    {value: 9, name: "Keski-Suomi"},
        ELY_V:      {value: 10, name: "Etelä-Pohjanmaa"},
        ELY_O:      {value: 12, name: "Pohjois-Pohjanmaa"},
        ELY_L:      {value: 14, name: "Lappi"}
    };

    root.NodeType = {
        NormalIntersection:             {value:1,   description:"Normaali tasoliittymä"},
        Roundabout:                     {value:3,   description:"Kiertoliittymä"},
        YIntersection:                  {value:4,   description:"Y-liittymä"},
        Interchange:                    {value:5,   description:"Eritasoliittymä"},
        RoadBoundary:                   {value:7,   description:"Maantien/kadun raja"},
        ELYBorder:                      {value:8,   description:"ELY-raja"},
        MultiTrackIntersection:         {value:10,  description:"Moniajoratainen liittymä"},
        DropIntersection:               {value:11,  description:"Pisaraliittymä"},
        AccessRoad:                     {value:12,  description:"Liityntätie"},
        EndOfRoad:                      {value:13,  description:"Tien loppu"},
        Bridge:                         {value:14,  description:"Silta"},
        MaintenanceOpening:             {value:15,  description:"Huoltoaukko"},
        PrivateRoad:                    {value:16,  description:"Yksityistie-tai katuliittymä"},
        StaggeredIntersection:          {value:17,  description:"Porrastettu liittymä"},
        UnknownNodeType:                {value:99,  description:"Ei määritelty"}
    };

    root.NodePointType = {
        RoadNodePoint:                  {value:1,   description:"Tien solmukohta"},
        CalculatedNodePoint:            {value:2,   description:"Laskettu solmukohta"},
        UnknownNodePointType:           {value:99,  description:"Ei määritelty"}
    };

    root.Tool = {
        Unknown:            {value: ""},
        Default:            {value: "Select"},
        Select:             {value: "Select"},
        Add:                {value: "Add"}
    };

    root.Discontinuity = {
        EndOfRoad:             {value : 1, description:"Tien loppu"},
        Discontinuous:         {value : 2, description:"Epäjatkuva"},
        ChangingELYCode:       {value : 3, description:"ELY:n raja"},
        MinorDiscontinuity:    {value : 4, description:"Lievä epäjatkuvuus"},
        Continuous:            {value : 5, description:"Jatkuva"},
        ParallelLink:          {value : 6, description:"Parallel Link"}

    };
})(window.LinkValues = window.LinkValues || {});

