// Wspolne ustawienia buildu (HwBuild) z submodule'a lib/ - ten sam plik,
// ktorego uzywa samodzielny build biblioteki.
Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "lib" / "sbt"

// HilBuild (rdzen vertebra-hil + adaptery IP) z vertebra-hil/sbt; korzysta z HwBuild.
Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "vertebra-hil" / "sbt"
