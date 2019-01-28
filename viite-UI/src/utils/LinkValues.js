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
        ComplimentaryLinkInterface: {value: 2,  descriptionFI: "Täydentävä",     description: "ComplimentaryLinkInterface"},
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
        SuravageLayer:              {value: 4},
        AnomalousMarkerLayer:       {value: 2},
        CalibrationPointLayer:      {value: 3},
        GeometryChangedLayer:       {value: 5},
        ReservedRoadLayer:          {value: 6},
        HistoricRoadLayer:          {value: 7},
        DirectionMarkerLayer:       {value: 8},
        GreenLayer:                 {value: 10},
        IndicatorLayer:             {value: 99}
    };

    root.RoadType = {
        PublicRoad:                     {value:1, description:"Yleinen tie"},
        FerryRoad:                      {value:2, description:"Lauttaväylä yleisellä tiellä"},
        MunicipalityStreetRoad:         {value:3, description:"Kunnan katuosuus"},
        PublicUnderConstructionRoad:    {value:4, description:"Yleisen tien työmaa"},
        PrivateRoadType:                {value:5, description:"Yksityistie"},
        UnknownOwnerRoad:               {value:9, description:"Omistaja selvittämättä"},
        Unknown:                        {value:99, description:"Ei määritelty"}
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
        ELY_U:      {value: 1},
        ELY_T:      {value: 2},
        ELY_KAS:    {value: 3},
        ELY_H:      {value: 4},
        ELY_SK:     {value: 8},
        ELY_KES:    {value: 9},
        ELY_V:      {value: 10},
        ELY_O:      {value: 12},
        ELY_L:      {value: 14}
    };

})(window.LinkValues = window.LinkValues || {});

