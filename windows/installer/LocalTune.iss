; The Windows installer, built with Inno Setup 6 (GitHub Actions does it; see .github/workflows/build.yml).
; By hand:  ISCC.exe /DAppVersion=1.0.0 windows\installer\LocalTune.iss
; It installs from the published app: dotnet publish windows\LocalTune -c Release -p:Platform=x64

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
; Never change AppId: it's how a new installer finds the old install and updates it.
AppId={{8F3C2A71-5D4E-4B9A-9C1F-2E7B6D0A4C53}
AppName=LocalTune
AppVersion={#AppVersion}
AppPublisher=SKVhacks
AppPublisherURL=https://github.com/SKVhacks/Local-Tune
AppSupportURL=https://github.com/SKVhacks/Local-Tune
; Installed for this user only, so no administrator prompt.
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\LocalTune
DisableProgramGroupPage=yes
; Always its own folder, so nothing else ever shares (or is removed with) it.
DisableDirPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Windows 10 1809 or later, as the Windows App SDK needs.
MinVersion=10.0.17763
OutputDir=Output
OutputBaseFilename=LocalTune-Setup-x64
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\LocalTune.exe
SetupIconFile=..\LocalTune\Assets\AppIcon.ico
UninstallDisplayName=LocalTune
; An open LocalTune is closed before its files are replaced.
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "..\LocalTune\bin\x64\Release\net10.0-windows10.0.26100.0\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\LocalTune"; Filename: "{app}\LocalTune.exe"
Name: "{autodesktop}\LocalTune"; Filename: "{app}\LocalTune.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\LocalTune.exe"; Description: "{cm:LaunchProgram,LocalTune}"; Flags: nowait postinstall skipifsilent
