// Module IDs from the pinned Discord Android 355010 Hermes bundle.
(function installSettingsEntry(global) {
    const SETTINGS_OVERVIEW = 19779;
    const REACT_NATIVE = 17;
    if (global.__discordRuSettingsEntry) return;
    global.__discordRuSettingsEntry = true;
    let define = global.__d;

    function configure(original) {
        if (typeof original !== "function") return original;
        return function defineWithSettingsEntry(factory, id, calls) {
            if (id !== SETTINGS_OVERVIEW) return original.apply(this, arguments);
            Object.defineProperty(global, "__d", {
                configurable: true, enumerable: true, writable: true, value: original
            });
            return original.call(this, function settingsFactory(root, require, importDefault, importAll, module, exports, deps) {
                factory.apply(this, arguments);
                const overview = module.exports.default;
                if (typeof overview !== "function" || !deps || deps[3] !== 21) return;
                const native = require(REACT_NATIVE);
                const { jsx, jsxs } = require(deps[3]);
                if (!native.View || !native.Pressable || !native.Text || !native.Linking
                    || typeof jsx !== "function" || typeof jsxs !== "function") return;
                module.exports.default = function SettingsWithProxy(props) {
                    const entry = jsxs(native.Pressable, {
                        accessibilityRole: "button",
                        onPress: () => native.Linking.openURL("discordru-internal://proxy").catch(() => {
                            native.Alert.alert("Прокси", "Не удалось открыть настройки прокси.");
                        }),
                        style: {
                            minHeight: 48,
                            flexDirection: "row",
                            alignItems: "center",
                            justifyContent: "space-between",
                            marginHorizontal: 16,
                            marginVertical: 8,
                            paddingHorizontal: 14,
                            borderRadius: 12,
                            backgroundColor: "#303448"
                        },
                        children: [
                            jsx(native.Text, { style: { color: "#f4f5f9", fontSize: 16 }, children: "Прокси" }),
                            jsx(native.Text, { accessible: false, style: { color: "#c0c6da", fontSize: 22 }, children: "›" })
                        ]
                    });
                    return jsxs(native.View, {
                        style: { flex: 1 },
                        children: [entry, jsx(native.View, { style: { flex: 1 }, children: jsx(overview, props) })]
                    });
                };
            }, id, calls);
        };
    }

    if (typeof define === "function") {
        global.__d = configure(define);
    } else {
        Object.defineProperty(global, "__d", {
            configurable: true,
            enumerable: true,
            get: () => define,
            set: (value) => { define = configure(value); }
        });
    }
})(globalThis);
