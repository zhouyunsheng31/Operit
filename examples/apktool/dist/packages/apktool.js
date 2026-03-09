"use strict";
/* METADATA
{
    "name": "apktool",
    "display_name": {
        "zh": "Apktool 直调",
        "en": "Apktool Direct Bridge"
    },
    "description": {
        "zh": "通过 ToolPkg.readResource + Java.loadJar 载入内置 apktool 运行时，并直接调用 brut.androlib.* 类完成 decode/build/framework 操作。",
        "en": "Load the bundled apktool runtime through ToolPkg.readResource + Java.loadJar, then directly call brut.androlib.* classes for decode/build/framework operations."
    },
    "enabledByDefault": false,
    "category": "System",
    "tools": [
        {
            "name": "usage_advice",
            "description": {
                "zh": "Apktool 直调建议：\\n- 本包不会启动 JVM 子进程，也不会走 runJar。\\n- 运行时会先释放一个包含 classes.dex 的 apktool runtime jar，然后用 Java.loadJar(...) 挂进 Java bridge。\\n- 脚本随后直接调用 brut.androlib.Config / ApkDecoder / ApkBuilder / Framework。\\n- 高级配置通过 config_json 传入，键名对应 brut.androlib.Config 的常用 setter。",
                "en": "Apktool direct-bridge advice:\\n- This package does not launch a JVM subprocess and does not use runJar.\\n- It first extracts an apktool runtime jar that contains classes.dex, then mounts it through Java.loadJar(...).\\n- The script directly calls brut.androlib.Config / ApkDecoder / ApkBuilder / Framework afterwards.\\n- Advanced configuration is passed through config_json, whose keys map to common brut.androlib.Config setters."
            },
            "parameters": [],
            "advice": true
        },
        {
            "name": "apktool_prepare_runtime",
            "description": {
                "zh": "释放并加载内置 apktool runtime jar，返回 runtime 路径、loadJar 结果以及可直接访问的类名。",
                "en": "Extract and load the bundled apktool runtime jar, then return the runtime path, loadJar result, and directly accessible class names."
            },
            "parameters": []
        },
        {
            "name": "apktool_decode",
            "description": {
                "zh": "直接调用 brut.androlib.ApkDecoder 解包 APK 到目录。",
                "en": "Directly call brut.androlib.ApkDecoder to decode an APK into a directory."
            },
            "parameters": [
                {
                    "name": "input_apk_path",
                    "description": {
                        "zh": "要解包的 APK 文件路径。",
                        "en": "Path to the APK file to decode."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "output_dir",
                    "description": {
                        "zh": "解包输出目录路径。",
                        "en": "Output directory path for decoded files."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "config_json",
                    "description": {
                        "zh": "可选，JSON 对象，映射到 brut.androlib.Config 的常用 setter。支持键：jobs、framework_directory、framework_tag、force、verbose、decode_sources、decode_resources、decode_assets、decode_resolve、baksmali_debug_mode、keep_broken_resources、ignore_raw_values、analysis_mode、no_apk、no_crunch、copy_original、debuggable、net_sec_conf、aapt_binary。",
                        "en": "Optional JSON object mapped to common brut.androlib.Config setters. Supported keys: jobs, framework_directory, framework_tag, force, verbose, decode_sources, decode_resources, decode_assets, decode_resolve, baksmali_debug_mode, keep_broken_resources, ignore_raw_values, analysis_mode, no_apk, no_crunch, copy_original, debuggable, net_sec_conf, aapt_binary."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "apktool_build",
            "description": {
                "zh": "直接调用 brut.androlib.ApkBuilder 从已解包目录回编 APK。",
                "en": "Directly call brut.androlib.ApkBuilder to build an APK from a decoded directory."
            },
            "parameters": [
                {
                    "name": "input_dir",
                    "description": {
                        "zh": "已解包工程目录路径。",
                        "en": "Path to the decoded project directory."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "output_apk_path",
                    "description": {
                        "zh": "输出 APK 文件路径。",
                        "en": "Output APK file path."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "config_json",
                    "description": {
                        "zh": "可选，JSON 对象，映射到 brut.androlib.Config 的常用 setter。构建时常用键包括 framework_directory、framework_tag、jobs、force、verbose、copy_original、debuggable、net_sec_conf、no_crunch、aapt_binary。",
                        "en": "Optional JSON object mapped to common brut.androlib.Config setters. Common build keys include framework_directory, framework_tag, jobs, force, verbose, copy_original, debuggable, net_sec_conf, no_crunch, aapt_binary."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "apktool_install_framework",
            "description": {
                "zh": "直接调用 brut.androlib.res.Framework.install 安装 framework APK。",
                "en": "Directly call brut.androlib.res.Framework.install to install a framework APK."
            },
            "parameters": [
                {
                    "name": "framework_apk_path",
                    "description": {
                        "zh": "framework APK 文件路径。",
                        "en": "Path to the framework APK file."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "config_json",
                    "description": {
                        "zh": "可选，JSON 对象，常用键包括 framework_directory、framework_tag、force、verbose。",
                        "en": "Optional JSON object. Common keys include framework_directory, framework_tag, force, and verbose."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "apktool_list_frameworks",
            "description": {
                "zh": "直接调用 brut.androlib.res.Framework 列出当前 framework 目录及已安装文件。",
                "en": "Directly call brut.androlib.res.Framework to list the current framework directory and installed files."
            },
            "parameters": [
                {
                    "name": "config_json",
                    "description": {
                        "zh": "可选，JSON 对象，常用键包括 framework_directory、framework_tag、verbose。",
                        "en": "Optional JSON object. Common keys include framework_directory, framework_tag, and verbose."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        }
    ]
}
*/
Object.defineProperty(exports, "__esModule", { value: true });
exports.usage_advice = usage_advice;
exports.apktool_prepare_runtime = apktool_prepare_runtime;
exports.apktool_decode = apktool_decode;
exports.apktool_build = apktool_build;
exports.apktool_install_framework = apktool_install_framework;
exports.apktool_list_frameworks = apktool_list_frameworks;
const PACKAGE_VERSION = "0.3.0";
const APKTOOL_VERSION = "3.0.1";
const APKTOOL_RUNTIME_RESOURCE_KEY = "apktool_runtime_android_jar";
const APKTOOL_RUNTIME_OUTPUT_FILE_NAME = "apktool-runtime-android.jar";
const APKTOOL_RUNTIME_SOURCE_ARTIFACT = "org.apktool:apktool-cli:3.0.1";
const APKTOOL_BRIDGE_CLASS_NAMES = [
    "brut.androlib.Config",
    "brut.androlib.ApkDecoder",
    "brut.androlib.ApkBuilder",
    "brut.androlib.res.Framework",
    "brut.androlib.Config$DecodeSources",
    "brut.androlib.Config$DecodeResources",
    "brut.androlib.Config$DecodeAssets",
    "brut.androlib.Config$DecodeResolve",
    "java.io.File"
];
function asText(value) {
    if (value === undefined || value === null) {
        return "";
    }
    return String(value);
}
function hasOwn(object, key) {
    return !!object && Object.prototype.hasOwnProperty.call(object, key);
}
function toErrorText(error) {
    if (error instanceof Error) {
        return error.message || String(error);
    }
    return asText(error) || "unknown error";
}
function requireText(params, key) {
    const value = asText(params && params[key]).trim();
    if (!value) {
        throw new Error(`Missing required parameter: ${key}`);
    }
    return value;
}
function parseConfigJson(params) {
    if (!hasOwn(params, "config_json")) {
        return {};
    }
    const raw = asText(params.config_json).trim();
    if (!raw) {
        return {};
    }
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("config_json must be a JSON object");
    }
    return parsed;
}
function parseBoolean(value, key) {
    if (typeof value === "boolean") {
        return value;
    }
    const normalized = asText(value).trim().toLowerCase();
    if (["1", "true", "yes", "y", "on"].includes(normalized)) {
        return true;
    }
    if (["0", "false", "no", "n", "off"].includes(normalized)) {
        return false;
    }
    throw new Error(`${key} must be a boolean`);
}
function parseInteger(value, key) {
    const parsed = Number(value);
    if (!Number.isInteger(parsed)) {
        throw new Error(`${key} must be an integer`);
    }
    return parsed;
}
function parseEnumToken(value) {
    return asText(value).trim().toLowerCase().replace(/[\s-]+/g, "_");
}
async function ensureRuntimeLoaded() {
    const runtimeJarPath = await ToolPkg.readResource(APKTOOL_RUNTIME_RESOURCE_KEY, APKTOOL_RUNTIME_OUTPUT_FILE_NAME);
    const loadInfo = Java.loadJar(runtimeJarPath);
    return {
        runtimeJarPath,
        loadInfo,
        sourceArtifact: APKTOOL_RUNTIME_SOURCE_ARTIFACT
    };
}
function getBridgeClasses() {
    return {
        File: Java.type("java.io.File"),
        Config: Java.type("brut.androlib.Config"),
        ApkDecoder: Java.type("brut.androlib.ApkDecoder"),
        ApkBuilder: Java.type("brut.androlib.ApkBuilder"),
        Framework: Java.type("brut.androlib.res.Framework"),
        DecodeSources: Java.type("brut.androlib.Config$DecodeSources"),
        DecodeResources: Java.type("brut.androlib.Config$DecodeResources"),
        DecodeAssets: Java.type("brut.androlib.Config$DecodeAssets"),
        DecodeResolve: Java.type("brut.androlib.Config$DecodeResolve")
    };
}
function resolveDecodeSources(classes, value) {
    const token = parseEnumToken(value);
    if (token === "full") {
        return classes.DecodeSources.FULL;
    }
    if (token === "only_main_classes" || token === "main") {
        return classes.DecodeSources.ONLY_MAIN_CLASSES;
    }
    if (token === "none") {
        return classes.DecodeSources.NONE;
    }
    throw new Error(`Unsupported decode_sources value: ${value}`);
}
function resolveDecodeResources(classes, value) {
    const token = parseEnumToken(value);
    if (token === "full") {
        return classes.DecodeResources.FULL;
    }
    if (token === "only_manifest" || token === "manifest") {
        return classes.DecodeResources.ONLY_MANIFEST;
    }
    if (token === "none") {
        return classes.DecodeResources.NONE;
    }
    throw new Error(`Unsupported decode_resources value: ${value}`);
}
function resolveDecodeAssets(classes, value) {
    const token = parseEnumToken(value);
    if (token === "full") {
        return classes.DecodeAssets.FULL;
    }
    if (token === "none") {
        return classes.DecodeAssets.NONE;
    }
    throw new Error(`Unsupported decode_assets value: ${value}`);
}
function resolveDecodeResolve(classes, value) {
    const token = parseEnumToken(value);
    if (token === "default") {
        return classes.DecodeResolve.DEFAULT;
    }
    if (token === "greedy") {
        return classes.DecodeResolve.GREEDY;
    }
    if (token === "lazy") {
        return classes.DecodeResolve.LAZY;
    }
    throw new Error(`Unsupported decode_resolve value: ${value}`);
}
function applyStringOption(config, configJson, key, setterName, target) {
    if (!hasOwn(configJson, key)) {
        return;
    }
    const value = asText(configJson[key]).trim();
    if (!value) {
        throw new Error(`${key} must not be blank`);
    }
    config[setterName](value);
    target[key] = value;
}
function applyIntegerOption(config, configJson, key, setterName, target) {
    if (!hasOwn(configJson, key)) {
        return;
    }
    const value = parseInteger(configJson[key], key);
    config[setterName](value);
    target[key] = value;
}
function applyBooleanOption(config, configJson, key, setterName, target) {
    if (!hasOwn(configJson, key)) {
        return;
    }
    const value = parseBoolean(configJson[key], key);
    config[setterName](value);
    target[key] = value;
}
function buildConfig(classes, params) {
    const configJson = parseConfigJson(params);
    const config = new classes.Config(APKTOOL_VERSION);
    const applied = {
        version: APKTOOL_VERSION
    };
    applyIntegerOption(config, configJson, "jobs", "setJobs", applied);
    applyStringOption(config, configJson, "framework_directory", "setFrameworkDirectory", applied);
    applyStringOption(config, configJson, "framework_tag", "setFrameworkTag", applied);
    applyStringOption(config, configJson, "aapt_binary", "setAaptBinary", applied);
    applyBooleanOption(config, configJson, "force", "setForced", applied);
    applyBooleanOption(config, configJson, "verbose", "setVerbose", applied);
    applyBooleanOption(config, configJson, "baksmali_debug_mode", "setBaksmaliDebugMode", applied);
    applyBooleanOption(config, configJson, "keep_broken_resources", "setKeepBrokenResources", applied);
    applyBooleanOption(config, configJson, "ignore_raw_values", "setIgnoreRawValues", applied);
    applyBooleanOption(config, configJson, "analysis_mode", "setAnalysisMode", applied);
    applyBooleanOption(config, configJson, "no_apk", "setNoApk", applied);
    applyBooleanOption(config, configJson, "no_crunch", "setNoCrunch", applied);
    applyBooleanOption(config, configJson, "copy_original", "setCopyOriginal", applied);
    applyBooleanOption(config, configJson, "debuggable", "setDebuggable", applied);
    applyBooleanOption(config, configJson, "net_sec_conf", "setNetSecConf", applied);
    if (hasOwn(configJson, "decode_sources")) {
        const value = asText(configJson.decode_sources).trim();
        config.setDecodeSources(resolveDecodeSources(classes, value));
        applied.decode_sources = value;
    }
    if (hasOwn(configJson, "decode_resources")) {
        const value = asText(configJson.decode_resources).trim();
        config.setDecodeResources(resolveDecodeResources(classes, value));
        applied.decode_resources = value;
    }
    if (hasOwn(configJson, "decode_assets")) {
        const value = asText(configJson.decode_assets).trim();
        config.setDecodeAssets(resolveDecodeAssets(classes, value));
        applied.decode_assets = value;
    }
    if (hasOwn(configJson, "decode_resolve")) {
        const value = asText(configJson.decode_resolve).trim();
        config.setDecodeResolve(resolveDecodeResolve(classes, value));
        applied.decode_resolve = value;
    }
    return {
        config,
        configJson,
        applied
    };
}
function javaFileListToPaths(list) {
    const paths = [];
    const size = Number(list.size());
    for (let index = 0; index < size; index += 1) {
        const file = list.get(index);
        paths.push(asText(file.getAbsolutePath()));
    }
    return paths;
}
function baseSuccessPayload(runtime) {
    return {
        success: true,
        packageName: "apktool",
        packageVersion: PACKAGE_VERSION,
        apktoolVersion: APKTOOL_VERSION,
        runtimeSourceArtifact: APKTOOL_RUNTIME_SOURCE_ARTIFACT,
        runtimeJarPath: runtime.runtimeJarPath,
        loadInfo: runtime.loadInfo
    };
}
function baseFailurePayload(error) {
    return {
        success: false,
        packageName: "apktool",
        packageVersion: PACKAGE_VERSION,
        apktoolVersion: APKTOOL_VERSION,
        runtimeSourceArtifact: APKTOOL_RUNTIME_SOURCE_ARTIFACT,
        error: toErrorText(error)
    };
}
async function usage_advice() {
    return {
        success: true,
        packageName: "apktool",
        packageVersion: PACKAGE_VERSION,
        apktoolVersion: APKTOOL_VERSION,
        runtimeSourceArtifact: APKTOOL_RUNTIME_SOURCE_ARTIFACT,
        runtimeLoadMode: "ToolPkg.readResource + Java.loadJar",
        note: "This package extracts a dex-jar runtime and directly calls brut.androlib.* classes through Java bridge instead of starting a JVM subprocess.",
        supportedConfigKeys: [
            "jobs",
            "framework_directory",
            "framework_tag",
            "force",
            "verbose",
            "decode_sources",
            "decode_resources",
            "decode_assets",
            "decode_resolve",
            "baksmali_debug_mode",
            "keep_broken_resources",
            "ignore_raw_values",
            "analysis_mode",
            "no_apk",
            "no_crunch",
            "copy_original",
            "debuggable",
            "net_sec_conf",
            "aapt_binary"
        ]
    };
}
async function apktool_prepare_runtime() {
    try {
        const runtime = await ensureRuntimeLoaded();
        return {
            ...baseSuccessPayload(runtime),
            bridgeClassNames: APKTOOL_BRIDGE_CLASS_NAMES,
            loadedCodePaths: Java.listLoadedCodePaths()
        };
    }
    catch (error) {
        return baseFailurePayload(error);
    }
}
async function apktool_decode(params) {
    try {
        const inputApkPath = requireText(params, "input_apk_path");
        const outputDir = requireText(params, "output_dir");
        const runtime = await ensureRuntimeLoaded();
        const classes = getBridgeClasses();
        const configInfo = buildConfig(classes, params);
        const decoder = new classes.ApkDecoder(new classes.File(inputApkPath), configInfo.config);
        decoder.decode(new classes.File(outputDir));
        return {
            ...baseSuccessPayload(runtime),
            operation: "decode",
            inputApkPath,
            outputDir,
            appliedConfig: configInfo.applied
        };
    }
    catch (error) {
        return {
            ...baseFailurePayload(error),
            operation: "decode"
        };
    }
}
async function apktool_build(params) {
    try {
        const inputDir = requireText(params, "input_dir");
        const outputApkPath = requireText(params, "output_apk_path");
        const runtime = await ensureRuntimeLoaded();
        const classes = getBridgeClasses();
        const configInfo = buildConfig(classes, params);
        const builder = new classes.ApkBuilder(new classes.File(inputDir), configInfo.config);
        builder.build(new classes.File(outputApkPath));
        return {
            ...baseSuccessPayload(runtime),
            operation: "build",
            inputDir,
            outputApkPath,
            appliedConfig: configInfo.applied
        };
    }
    catch (error) {
        return {
            ...baseFailurePayload(error),
            operation: "build"
        };
    }
}
async function apktool_install_framework(params) {
    try {
        const frameworkApkPath = requireText(params, "framework_apk_path");
        const runtime = await ensureRuntimeLoaded();
        const classes = getBridgeClasses();
        const configInfo = buildConfig(classes, params);
        const framework = new classes.Framework(configInfo.config);
        framework.install(new classes.File(frameworkApkPath));
        const frameworkDirectory = asText(framework.getDirectory().getAbsolutePath());
        return {
            ...baseSuccessPayload(runtime),
            operation: "install_framework",
            frameworkApkPath,
            frameworkDirectory,
            installedFrameworks: javaFileListToPaths(framework.listDirectory()),
            appliedConfig: configInfo.applied
        };
    }
    catch (error) {
        return {
            ...baseFailurePayload(error),
            operation: "install_framework"
        };
    }
}
async function apktool_list_frameworks(params) {
    try {
        const runtime = await ensureRuntimeLoaded();
        const classes = getBridgeClasses();
        const configInfo = buildConfig(classes, params);
        const framework = new classes.Framework(configInfo.config);
        const frameworkDirectory = asText(framework.getDirectory().getAbsolutePath());
        const installedFrameworks = javaFileListToPaths(framework.listDirectory());
        return {
            ...baseSuccessPayload(runtime),
            operation: "list_frameworks",
            frameworkDirectory,
            installedFrameworks,
            frameworkCount: installedFrameworks.length,
            appliedConfig: configInfo.applied
        };
    }
    catch (error) {
        return {
            ...baseFailurePayload(error),
            operation: "list_frameworks"
        };
    }
}
