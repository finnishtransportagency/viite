(function (root) {
  root.RoadAddressChangeType = {
    NotHandled: {value: 0, description: "NotHandled", transitionFrom: [0], displayText: "Käsittelemätön"},
    Unchanged: {value: 1, description: "Unchanged", transitionFrom: [0, 1, 3, 5], displayText: "Ennallaan"},
    New: {value: 2, description: "New", transitionFrom: [99, 2], displayText: "Uusi"},
    Transfer: {value: 3, description: "Transfer", transitionFrom: [0, 1, 3, 5], displayText: "Siirto"},
    Numbering: {value: 4, description: "Numbering", transitionFrom: [0, 1, 3, 4, 5], displayText: "Numerointi"},
    Terminated: {value: 5, description: "Terminated", transitionFrom: [0, 1, 3, 5], displayText: "Lakkautus"},
    Revert: {value: 6, description: "Revert", transitionFrom: [1, 2, 3, 4, 5, 6], displayText: "Palautus aihioksi tai tieosoitteettomaksi"},
    Undefined: {value: 99, description: "?", transitionFrom: []}
  };

  root.ChangeType = {
    Unchanged: {value: 1, description: "Unchanged", displayText: "Ennallaan"},
    New: {value: 2, description: "New", displayText: "Uusi"},
    Transfer: {value: 3, description: "Transfer", displayText: "Siirto"},
    Numbering: {value: 4, description: "Numbering", displayText: "Numerointi"},
    Terminated: {value: 5, description: "Terminated", displayText: "Lakkautus"}
  };

  root.LinkGeomSource = {
    NormalLinkInterface: {value: 1, descriptionFI: "MML", description: "NormalLinkInterface"},
    ComplementaryLinkInterface: {value: 2, descriptionFI: "Täydentävä", description: "ComplementaryLinkInterface"},
    SuravageLinkInterface: {value: 3, descriptionFI: "Suravage", description: "SuravageLinkInterface"},
    FrozenLinkInterface: {value: 4, descriptionFI: "MML jäädytetty", description: "FrozenLinkInterface"},
    HistoryLinkInterface: {value: 5, descriptionFI: "MML historia", description: "HistoryLinkInterface"},
    Unknown: {value: 99, descriptionFI: "Tuntematon", description: "Unknown"}
  };

  root.lifecycleStatus = {
      Planned: {value: 1, description: "Planned"},
      UnderConstruction: {value: 2, description: "UnderConstruction"},
      InUse: {value: 3, description: "InUse"},
      UnknownConstructionType: {value: 99, description: "UnknownConstructionType"}
  };

  root.SelectionType = {
    All: {value: 0, description: "all"},
    Unknown: {value: 99, description: "unknown"}
  };

  root.RoadClass = {
    HighwayClass: {value: 1, description: "HighwayClass"},
    MainRoadClass: {value: 2, description: "MainRoadClass"},
    RegionalClass: {value: 3, description: "RegionalClass"},
    ConnectingClass: {value: 4, description: "ConnectingClass"},
    MinorConnectingClass: {value: 5, description: "MinorConnectingClass"},
    StreetClass: {value: 6, description: "StreetClass"},
    RampsAndRoundAboutsClass: {value: 7, description: "RampsAndRoundAboutsClass"},
    PedestrianAndBicyclesClass: {value: 8, description: "PedestrianAndBicyclesClass"},
    WinterRoadsClass: {value: 9, description: "WinterRoadsClass"},
    PathsClass: {value: 10, description: "PathsClass"},
    PrivateRoadClass: {value: 12, description: "PrivateRoadClass"},
    NoClass: {value: 99, description: "NoClass"}
  };

  root.LifeCycleStatus = {
    UnderConstructionPrivate: {value: 0, description: "Rakenteilla (kunta/yksityinen)"},
    UnderConstructionState: {value: 1, description: "Rakenteilla (valtio)"}
  };

  root.TrafficDirection = {
    BothDirections: {value: 2, description: "Molempiin suuntiin"},
    AgainstDigitizing: {value: 3, description: "Digitointisuuntaa vastaan"},
    TowardsDigitizing: {value: 4, description: "Digitointisuuntaan"},
    UnknownDirection: {value: 99, description: "Tuntemattomaan suuntaan"}
  };

  root.SideCode = {
    BothDirections: {value: 1, description: "BothDirections"},
    TowardsDigitizing: {value: 2, description: "TowardsDigitizing"},
    AgainstDigitizing: {value: 3, description: "AgainstDigitizing"},
    Unknown: {value: 99, description: "Unknown"}
  };

  root.CalibrationCode = {
    None: {value: 0, description: "None"},
    AtEnd: {value: 1, description: "AtEnd"},
    AtBeginning: {value: 2, description: "AtBeginning"},
    AtBoth: {value: 3, description: "AtBoth"}
  };

  root.ProjectStatus = {
    ErrorInViite: {value: 0, description: "Virhe Viite-sovelluksessa"},
    Incomplete: {value: 1, description: "Keskeneräinen"},
    Deleted: {value: 7, description: "Poistettu projekti"},
    InUpdateQueue: {value: 10, description: "Odottaa tieverkolle päivittämistä"},
    UpdatingToRoadNetwork: {value: 11, description: "Päivitetään tieverkolle"},
    Accepted: {value: 12, description: "Hyväksytty"},
    Unknown: {value: 99, description: "Tuntematon"}
  };

  root.ProjectStatusToDisplay = [root.ProjectStatus.Incomplete.value,
    root.ProjectStatus.InUpdateQueue.value, root.ProjectStatus.UpdatingToRoadNetwork.value,
    root.ProjectStatus.ErrorInViite.value];

  root.Track = {
    Combined: {value: 0, description: "Combined"},
    RightSide: {value: 1, description: "RightSide"},
    LeftSide: {value: 2, description: "LeftSide"},
    Unknown: {value: 99, description: "Unknown"}
  };

  root.NodesAndJunctionsZIndex = {
    DirectionMarker: {value: 140},
    NodePointTemplate: {value: 160, selected: 165},
    NodeMarker: {value: 170, selected: 175},
    JunctionTemplate: {value: 180, selected: 185},
    JunctionMarker: {value: 190, selected: 195}
  };

  root.ViewModeZIndex = {
    BorderStyle: {value: 1},
    UnAddressedOther: {border: 10, stroke: 11, fill: 12},
    UnAddressedState: {border: 20, stroke: 20, fill: 20},
    UnAddressedOtherUnderConstruction: {border: 30, stroke: 31, fill: 32},
    UnAddressedStateUnderConstruction: {border: 40, stroke: 41, fill: 42},
    PedestrianAndBicycleRoad: {border: 50, stroke: 51, fill: 52},
    PrivateRoad: {border: 60, stroke: 61, fill: 62},
    Street: {border: 70, stroke: 71, fill: 72},
    Ramp: {border: 80, stroke: 81, fill: 82},
    ConnectingRoad: {border: 90, stroke: 91, fill: 92},
    RegionalRoad: {border: 100, stroke: 101, fill: 102},
    MainRoad: {border: 110, stroke: 111, fill: 112},
    Highway: {border: 120, stroke: 121, fill: 122},
    ReservedRoad: {value: 130},
    DirectionMarker: {value: 140},
    CalibrationPoint: {value: 150}
  };

  root.ProjectModeZIndex = {
    BorderStyle: {value: 1},
    NotInProjectRoadLinks: {border: 10, stroke: 11, fill: 12},
    TerminatedProjectLinks: {border: 20, stroke: 21, fill: 22},
    UnAddressedOther: {border: 30, stroke: 31, fill: 32},
    UnAddressedState: {border: 40, stroke: 41, fill: 42},
    UnAddressedUnderConstructionOther: {border: 50, stroke: 51, fill: 52},
    UnAddressedUnderConstructionState: {border: 60, stroke: 61, fill: 62},
    TransferredProjectLinks: {border: 70, stroke: 71, fill: 72},
    UnchangedProjectLinks: {border: 70, stroke: 71, fill: 72},
    NumberingProjectLinks: {border: 70, stroke: 71, fill: 72},
    NewProjectLinks: {border: 70, stroke: 71, fill: 72},
    NotHandledProjectLinks: {border: 80, stroke: 81, fill: 82},
    CalibrationPoint: {value: 90},
    SelectedProjectLink: {value: 100},
    DirectionMarker: {value: 110}
  };

  root.AdministrativeClass = {
    Empty: {value: 0, description: "", displayText: "--", textValue: ""},
    PublicRoad: {value: 1, description: "Yleinen tie", displayText: "1 Valtio", textValue: "Valtio"},
    MunicipalityStreetRoad: {value: 2, description: "Kunnan katuosuus", displayText: "2 Kunta", textValue: "Kunta"},
    PrivateRoad: {value: 3, description: "Yksityistie", displayText: "3 Yksityinen", textValue: "Yksityinen"},
    Unknown: {value: 99, description: "Ei määritelty", displayText: "--", textValue: ""}
  };

  root.AdministrativeClassShort = {
    PublicRoad: {value: 1, description: "valtio"},
    MunicipalityStreetRoad: {value: 2, description: "kunta"},
    PrivateRoad: {value: 3, description: "yksit."}
  };

  root.RoadLinkType = {
    UnknownRoadLinkType: {value: 0, description: "UnknownRoadLinkType"},
    NormalRoadLinkType: {value: 1, description: "NormalRoadLinkType"},
    ComplementaryRoadLinkType: {value: 3, description: "ComplementaryRoadLinkType"},
    SuravageRoadLink: {value: 4, description: "SuravageRoadLink"}
  };

  root.RoadNameSource = {
    UnknownSource: {value: 99, description: "Unknown Source"},
    ProjectLinkSource: {value: 0, description: "Project Link Source"},
    RoadAddressSource: {value: 1, description: "Road Name Source"}
  };

  root.ProjectError = {
    TerminationContinuity: {value: 18},
    DoubleEndOfRoad: {value: 19},
    RoadNotReserved: {value: 27}
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

  root.BlackUnderlineAdministrativeClasses = [root.AdministrativeClass.MunicipalityStreetRoad.value, root.AdministrativeClass.PrivateRoad.value];

  root.ElyCodes = {
    ELY_U: {value: 1, name: "Uusimaa", shortName: "UUD"},
    ELY_T: {value: 2, name: "Varsinais-Suomi", shortName: "VAR"},
    ELY_KAS: {value: 3, name: "Kaakkois-Suomi", shortName: "KAS"},
    ELY_H: {value: 4, name: "Pirkanmaa", shortName: "PIR"},
    ELY_SK: {value: 8, name: "Pohjois-Savo", shortName: "POS"},
    ELY_KES: {value: 9, name: "Keski-Suomi", shortName: "KES"},
    ELY_V: {value: 10, name: "Etelä-Pohjanmaa", shortName: "EPO"},
    ELY_O: {value: 12, name: "Pohjois-Pohjanmaa", shortName: "POP"},
    ELY_L: {value: 14, name: "Lappi", shortName: "LAP"}
  };

  root.NodeType = {
    NormalIntersection: {value: 1, description: "Normaali tasoliittymä"},
    Roundabout: {value: 3, description: "Kiertoliittymä"},
    YIntersection: {value: 4, description: "Y-liittymä"},
    Interchange: {value: 5, description: "Eritasoliittymä"},
    RoadBoundary: {value: 7, description: "Hallinnollinen raja"},
    ELYBorder: {value: 8, description: "ELY-raja"},
    MultiTrackIntersection: {value: 10, description: "Moniajoratainen liittymä"},
    AccessRoad: {value: 12, description: "Liityntätie"},
    EndOfRoad: {value: 13, description: "Tien alku/loppu"},
    Bridge: {value: 14, description: "Silta"},
    MaintenanceOpening: {value: 15, description: "Huoltoaukko"},
    PrivateRoad: {value: 16, description: "Yksityistie- tai katuliittymä"},
    StaggeredIntersection: {value: 17, description: "Porrastettu liittymä"},
    Ferry: {value: 18, description: "Lautta"},
    UnknownNodeType: {value: 99, description: "Ei määritelty"}
  };

  root.NodePointType = {
    RoadNodePoint: {value: 1, description: "Tien solmukohta"},
    CalculatedNodePoint: {value: 2, description: "Laskettu solmukohta"},
    UnknownNodePointType: {value: 99, description: "Ei määritelty"}
  };

  root.BeforeAfter = {
    Before: {value: 1, description: "Ennen", displayLetter: "E"},
    After: {value: 2, description: "Jälkeen", displayLetter: "J"}
  };

  root.Tool = {
    Select: {value: "Select", description: 'Solmun valinta'},
    Attach: {value: "Attach", alias: ["Select"]},
    Add: {value: "Add"},
    Default: {value: "Default", alias: ["Select"]},
    Unknown: {value: ""}
  };

  root.Discontinuity = {
    EndOfRoad: {value: 1, description: "Tien loppu"},
    Discontinuous: {value: 2, description: "Epäjatkuva"},
    ChangingELYCode: {value: 3, description: "ELY:n raja"},
    MinorDiscontinuity: {value: 4, description: "Lievä epäjatkuvuus"},
    Continuous: {value: 5, description: "Jatkuva"}

  };
}(window.ViiteEnumerations = window.ViiteEnumerations || {}));

