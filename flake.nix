{
  description = "Android toolchain to build the VTLo_unrestrict3d app";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37" ];
            buildToolsVersions = [ "37.0.0" ];
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
          };
          sdkRoot = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk21
              pkgs.gradle_9
              android.androidsdk
            ];
            ANDROID_SDK_ROOT = sdkRoot;
            ANDROID_HOME = sdkRoot;
            JAVA_HOME = "${pkgs.jdk21}";
          };
        }
      );
    };
}
