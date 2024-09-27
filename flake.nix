{
  description = "A flake file for this repo";

  inputs = {
    nixpkgs.url = github:NixOS/nixpkgs/nixos-24.05;
    flake-utils.url = github:numtide/flake-utils;
    devshell.url = github:numtide/devshell;
  };

  outputs = { self, nixpkgs, flake-utils, devshell }:
    flake-utils.lib.eachDefaultSystem
      (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
            };
            overlays = [
              devshell.overlays.default
            ];          };
          pnpm = pkgs.writeShellScriptBin "pnpm" ''
            npx pnpm@9.7.1 "$@";
          '';
          baseBuildInputs = with pkgs; [
            # Runtimes
            nodejs_20
            babashka
            clojure

            # Scripts
            pnpm

            # keep this line if you use bash
            bashInteractive
          ];
        in
          {
            devShells.base = pkgs.devshell.mkShell {
              packages = baseBuildInputs;
            };
            devShells.default = pkgs.devshell.mkShell {
              name = "dag-o-bert";
              packages = baseBuildInputs;
            };
          }
      );
}
