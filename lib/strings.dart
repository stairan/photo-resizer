/// All user-visible text (Hungarian only).
class S {
  static const appTitle = 'Fotó Kicsinyítő';

  static const sourceTitle = 'Mappa';
  static const pickFolder = 'Mappa kiválasztása';
  static const changeFolder = 'Másik mappa';
  static const noFolder = 'Még nincs mappa kiválasztva.';
  static const scanning = 'Mappa vizsgálata…';

  static String scanResult(int images, String imageSize, int others, String otherSize) =>
      '$images kép ($imageSize), $others egyéb fájl ($otherSize)';

  static const modeTitle = 'Mentés módja';
  static const modeOverwrite = 'Eredeti képek felülírása';
  static const modeNewFolder = 'Új mappába mentés';
  static const overwriteWarningTitle = 'Figyelem!';
  static const overwriteWarning =
      'Az eredeti képeket a program véglegesen lecseréli a kisebb felbontású '
      'változatra. Ez nem visszavonható, az eredeti nagy felbontású képek elvesznek.';

  static const destTitle = 'Célmappa';
  static const pickDest = 'Célmappa kiválasztása';
  static const destSameAsSource = 'A célmappa nem lehet ugyanaz, mint a forrásmappa.';
  static const copyOthers = 'A nem kép fájlok másolása is';
  static const copyOthersHint = 'Dokumentumok, videók és minden más átkerül az új mappába.';

  static const resolutionTitle = 'Felbontás';
  static String sizeInfo(int px) =>
      'A kép hosszabbik oldala $px képpont lesz (4:3 képnél $px×${(px * 3 / 4).round()}). '
      'A már ennél kisebb képek nem változnak.';
  static String quality(int q) => 'JPEG minőség: $q';

  static const testRun = 'Teszt (10 kép)';
  static const start = 'Indítás';

  static const confirmOverwriteTitle = 'Biztosan felülírod az eredeti képeket?';
  static String confirmOverwriteBody(String label, int count, bool test, int px) =>
      'Mappa: $label\n\n'
      '${test ? 'Legfeljebb 10 kép' : 'Legfeljebb $count kép'} lesz átméretezve $px képpontra, és az eredeti '
      'fájlokat a kisebb változat váltja fel.\n\n'
      'Ez NEM vonható vissza.';
  static const confirmCancel = 'Mégse';
  static const confirmGo = 'Igen, felülírom';

  static const running = 'Folyamatban…';
  static const cancel = 'Megszakítás';
  static const cancelling = 'Megszakítás…';
  static const keepAppOpen = 'A művelet a háttérben is fut, az értesítésben követheted.';
  static String progress(int done, int total) => '$done / $total fájl';
  static String resizedCount(int n) => 'Átméretezve: $n';
  static String savedSpace(String s) => 'Megtakarított hely: $s';

  static const doneTitle = 'Kész';
  static const cancelledTitle = 'Megszakítva';
  static String rResized(int n) => 'Átméretezett képek: $n';
  static String rSkippedSmall(int n) => 'Már elég kicsi, kihagyva: $n';
  static String rSkippedNotSmaller(int n) => 'Nem lett kisebb, érintetlenül hagyva: $n';
  static String rSkippedExisting(int n) => 'Már létezett a célmappában: $n';
  static String rOthers(int n) => 'Átmásolt egyéb fájl: $n';
  static String rFailed(int n) => 'Hibás fájl: $n';
  static String rSaved(String from, String to, String saved) =>
      'Méret: $from → $to (megtakarítás: $saved)';
  static const failuresTitle = 'Hibás fájlok listája';
  static const newTask = 'Új feladat';
  static const errorPrefix = 'Hiba: ';
}
