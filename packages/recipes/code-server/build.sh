# FoldCode: the VS Code backend (code-server) for the io.fold prefix.
# Based on the code-server recipe of the Termux User Repository (termux-user-repository/tur;
# package recipes there are licensed like the package itself, here MIT), adapted to FoldCode:
# runs on FoldCode's nodejs-lts (24.x) instead of TUR's nodejs-24, and keeps VS Code's
# user data under ~/.config on Android.
TERMUX_PKG_HOMEPAGE=https://github.com/coder/code-server
TERMUX_PKG_DESCRIPTION="VS Code backend (code-server) for the FoldCode app"
TERMUX_PKG_LICENSE="MIT"
TERMUX_PKG_MAINTAINER="FoldCode"
TERMUX_PKG_VERSION="4.145.2"
TERMUX_PKG_SRCURL=git+https://github.com/coder/code-server
TERMUX_PKG_DEPENDS="libandroid-spawn, libsecret, krb5, nodejs-lts, ripgrep"
TERMUX_PKG_BUILD_IN_SRC=true
TERMUX_PKG_HOSTBUILD=true
TERMUX_PKG_NO_STATICSPLIT=true
TERMUX_PKG_EXCLUDED_ARCHES="arm, i686"
TERMUX_PKG_ON_DEVICE_BUILD_NOT_SUPPORTED=true
# Bundled extensions carry prebuilt binaries for other platforms that are never loaded on
# Android; they reference symbols the ELF check would flag.
TERMUX_PKG_UNDEF_SYMBOLS_FILES=all

_NODE_HOST_VERSION=24.22.2
_NODE_HOST_SHA256=48cc0e21e048790e54c0cf5749c2725005313d652651c40b7a21feb9697aaf75

termux_step_post_get_source() {
	local f
	for f in $(cat ./patches/series); do
		echo "Applying code-server patch: $(basename "$f")"
		patch -d . -p1 < "./patches/$f"
	done

	# The target Node (nodejs-lts) is 24.x; code-server must agree.
	local node_major
	node_major=$(cut -d. -f1 .node-version)
	if [ "$node_major" != 24 ]; then
		termux_error_exit "code-server wants Node $node_major, nodejs-lts is 24"
	fi

	sed -i "s/--max-old-space-size=8192 / /g" lib/vscode/package.json
	rm -rf "$TERMUX_HOSTBUILD_MARKER"
}

_foldcode_setup_host_node() {
	local dir="$TERMUX_PKG_CACHEDIR/build-tools/nodejs-$_NODE_HOST_VERSION"
	if [ ! -x "$dir/bin/node" ]; then
		mkdir -p "$dir"
		local tarball="$TERMUX_PKG_TMPDIR/nodejs-$_NODE_HOST_VERSION.tar.xz"
		termux_download "https://nodejs.org/dist/v$_NODE_HOST_VERSION/node-v$_NODE_HOST_VERSION-linux-x64.tar.xz" \
			"$tarball" "$_NODE_HOST_SHA256"
		tar -xf "$tarball" -C "$dir" --strip-components=1
	fi
	export PATH="$dir/bin:$PATH"
}

# Builds code-server and VS Code (JavaScript) on the build machine.
termux_step_host_build() {
	export DISABLE_V8_COMPILE_CACHE=1
	export VERSION=$TERMUX_PKG_VERSION
	mv "$TERMUX_PREFIX/bin" "$TERMUX_PREFIX/bin.bp"
	env -i PATH="$PATH" sudo apt update
	env -i PATH="$PATH" sudo apt install -yq libxkbfile-dev libsecret-1-dev libkrb5-dev
	_foldcode_setup_host_node
	cd "$TERMUX_PKG_SRCDIR"
	npm ci
	npm install ternary-stream
	npm run build
	npm run build:vscode
	npm run release
	mv "$TERMUX_PREFIX/bin.bp" "$TERMUX_PREFIX/bin"
}

termux_step_configure() {
	_foldcode_setup_host_node
}

# Cross-compiles the native Node modules (node-pty, spdlog, watcher, argon2, …) for Android.
termux_step_make() {
	export DISABLE_V8_COMPILE_CACHE=1
	export VERSION=$TERMUX_PKG_VERSION
	mv "$TERMUX_PREFIX/bin" "$TERMUX_PREFIX/bin.bp"

	export npm_config_arch=arm64
	export npm_config_build_from_source=true

	# node-pty and friends link -lrt, which bionic folds into libc; point it at libandroid-spawn.
	rm -f "$TERMUX_PREFIX"/lib/librt.{so,a}
	echo "INPUT(-landroid-spawn)" > "$TERMUX_PREFIX/lib/librt.so"

	rsync -a release/ release-standalone
	mkdir -p release-standalone/bin release-standalone/lib
	rsync ci/build/code-server.sh release-standalone/bin/code-server
	chmod 755 release-standalone/bin/code-server
	rsync "$(node -p process.execPath)" release-standalone/lib/node
	chmod 755 release-standalone/lib/node
	export FORCE_NODE_VERSION=true
	pushd release-standalone
	npm install --unsafe-perm --omit=dev
	rm -fr ./lib/vscode/extensions/node_modules/.bin
	popd

	mv "$TERMUX_PREFIX/bin.bp" "$TERMUX_PREFIX/bin"
}

termux_step_make_install() {
	npm version --prefix release-standalone --allow-same-version "$VERSION"

	# Drop the host Node that the release bundles; FoldCode's nodejs-lts replaces it.
	rm ./release-standalone/lib/node
	mkdir -p "$TERMUX_PREFIX/lib/code-server"
	cp -Rf ./release-standalone/* "$TERMUX_PREFIX/lib/code-server/"
	ln -sf "$TERMUX_PREFIX/bin/node" "$TERMUX_PREFIX/lib/code-server/lib/node"

	# @vscode/ripgrep-universal resolves bin/<platform>-<arch>/rg; process.platform is
	# "android" here. Point every bundled rg (all for other platforms) at the prefix's rg.
	local rg_dir="$TERMUX_PREFIX/lib/code-server/lib/vscode/node_modules/@vscode/ripgrep-universal/bin"
	mkdir -p "$rg_dir/android-arm64"
	local d
	for d in "$rg_dir"/*/; do
		rm -f "${d}rg" "${d}rg.exe"
		ln -sf "$TERMUX_PREFIX/bin/rg" "${d}rg"
	done

	cat > "$TERMUX_PREFIX/bin/code-server" <<- EOF
	#!$TERMUX_PREFIX/bin/sh
	exec $TERMUX_PREFIX/lib/code-server/bin/code-server "\$@"
	EOF
	chmod 755 "$TERMUX_PREFIX/bin/code-server"

	rm -f "$TERMUX_PREFIX/lib/librt.so"
}
