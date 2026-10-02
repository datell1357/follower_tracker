"""Generate the native app and widget project using only the Python standard library."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
objects = {}


def identifier(name):
    return hashlib.sha1(name.encode()).hexdigest()[:24].upper()


def quote(value):
    return json.dumps(str(value), ensure_ascii=False)


def add(name, body):
    key = identifier(name)
    objects[key] = body
    return key


def array(values):
    return "(" + ",".join(values) + ",)" if values else "()"


def file(path, kind):
    return add("file:" + path, f"isa = PBXFileReference; lastKnownFileType = {kind}; path = {quote(path)}; sourceTree = SOURCE_ROOT;")


shared = [str(path.relative_to(ROOT)) for path in sorted((ROOT / "Shared").glob("*.swift"))]
app = [str(path.relative_to(ROOT)) for path in sorted((ROOT / "App").glob("*.swift"))]
widget = [str(path.relative_to(ROOT)) for path in sorted((ROOT / "Widget").glob("*.swift"))]
tests = [str(path.relative_to(ROOT)) for path in sorted((ROOT / "Tests").glob("*.swift"))]
refs = {path: file(path, "sourcecode.swift") for path in shared + app + widget + tests}
resources = {"App/Assets.xcassets": file("App/Assets.xcassets", "folder.assetcatalog"),
             "Shared/PrivacyInfo.xcprivacy": file("Shared/PrivacyInfo.xcprivacy", "text.xml"),
             "../shared/web-session-capture.js": file("../shared/web-session-capture.js", "sourcecode.javascript")}
config = file("Config/Base.xcconfig", "text.xcconfig")
metadata = [file(path, "text.plist.entitlements" if path.endswith("entitlements") else "text.plist.xml")
            for path in ["App/Info.plist", "Widget/Info.plist", "Config/Shared.entitlements"]]
package = add("local-package", 'isa = XCLocalSwiftPackageReference; relativePath = FollowerCore;')
product_app = add("product-app", 'isa = PBXFileReference; explicitFileType = wrapper.application; path = FollowerTracker.app; sourceTree = BUILT_PRODUCTS_DIR;')
product_widget = add("product-widget", 'isa = PBXFileReference; explicitFileType = "wrapper.app-extension"; path = FollowerTrackerWidget.appex; sourceTree = BUILT_PRODUCTS_DIR;')
product_tests = add("product-tests", 'isa = PBXFileReference; explicitFileType = wrapper.cfbundle; path = FollowerTrackerTests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
products = add("products", f'isa = PBXGroup; name = Products; children = {array([product_app, product_widget, product_tests])}; sourceTree = "<group>";')
group = add("main-group", f'isa = PBXGroup; children = {array(list(refs.values()) + list(resources.values()) + [config] + metadata + [products])}; sourceTree = "<group>";')


def configurations(name, extra):
    ids = []
    for mode in ["Debug", "Release"]:
        settings = {"SDKROOT": "iphoneos", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator", "CLANG_ENABLE_MODULES": "YES",
                    "ENABLE_USER_SCRIPT_SANDBOXING": "YES", "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if mode == "Debug" else "-O",
                    "SWIFT_COMPILATION_MODE": "singlefile" if mode == "Debug" else "wholemodule",
                    "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG" if mode == "Debug" else "",
                    "ENABLE_TESTABILITY": "YES" if mode == "Debug" else "NO",
                    "ONLY_ACTIVE_ARCH": "YES" if mode == "Debug" else "NO", **extra}
        body = " ".join(f"{key} = {quote(value)};" for key, value in settings.items())
        ids.append(add(f"configuration:{name}:{mode}", f'isa = XCBuildConfiguration; baseConfigurationReference = {config}; buildSettings = {{ {body} }}; name = {mode};'))
    return add(f"config-list:{name}", f'isa = XCConfigurationList; buildConfigurations = {array(ids)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')


def phase(name, kind, files):
    build_files = [add(f"build:{name}:{path}", f'isa = PBXBuildFile; fileRef = {ref};') for path, ref in files]
    return add("phase:" + name, f'isa = {kind}; buildActionMask = 2147483647; files = {array(build_files)}; runOnlyForDeploymentPostprocessing = 0;')


def target(name, sources, resource_files, product, extension=False, extra_phases=None, dependencies=None):
    sources_id = phase(name + "-sources", "PBXSourcesBuildPhase", [(path, refs[path]) for path in sources])
    resources_id = phase(name + "-resources", "PBXResourcesBuildPhase", resource_files)
    dependency = add("package-product:" + name, f'isa = XCSwiftPackageProductDependency; package = {package}; productName = FollowerCore;')
    link = add("link:" + name, f'isa = PBXBuildFile; productRef = {dependency};')
    frameworks = add("frameworks:" + name, f'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = {array([link])}; runOnlyForDeploymentPostprocessing = 0;')
    settings = {"PRODUCT_NAME": "$(TARGET_NAME)", "PRODUCT_BUNDLE_IDENTIFIER": "dev.datell.followertracker" + (".widget" if extension else ""),
                "INFOPLIST_FILE": "Widget/Info.plist" if extension else "App/Info.plist", "GENERATE_INFOPLIST_FILE": "NO",
                "CODE_SIGN_ENTITLEMENTS": "Config/Shared.entitlements", "OTHER_LDFLAGS": "$(inherited) -lsqlite3",
                "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks" + (" @executable_path/../../Frameworks" if extension else ""),
                "APPLICATION_EXTENSION_API_ONLY": "YES" if extension else "NO", "SKIP_INSTALL": "YES" if extension else "NO"}
    if not extension:
        settings["ASSETCATALOG_COMPILER_APPICON_NAME"] = "AppIcon"
    configuration = configurations(name, settings)
    product_type = "com.apple.product-type.app-extension" if extension else "com.apple.product-type.application"
    return add("target:" + name, f'isa = PBXNativeTarget; buildConfigurationList = {configuration}; buildPhases = {array([sources_id, frameworks, resources_id] + (extra_phases or []))}; buildRules = (); dependencies = {array(dependencies or [])}; name = {name}; packageProductDependencies = {array([dependency])}; productName = {name}; productReference = {product}; productType = {quote(product_type)};')


widget_target = target("FollowerTrackerWidget", shared + widget, [(path, ref) for path, ref in resources.items() if path.endswith(".xcprivacy")], product_widget, extension=True)
proxy = add("widget-proxy", f'isa = PBXContainerItemProxy; containerPortal = {identifier("project")}; proxyType = 1; remoteGlobalIDString = {widget_target}; remoteInfo = FollowerTrackerWidget;')
dependency = add("widget-dependency", f'isa = PBXTargetDependency; target = {widget_target}; targetProxy = {proxy};')
embed_file = add("embed-widget", f'isa = PBXBuildFile; fileRef = {product_widget}; settings = {{ ATTRIBUTES = (RemoveHeadersOnCopy,); }};')
embed = add("embed-phase", f'isa = PBXCopyFilesBuildPhase; buildActionMask = 2147483647; dstPath = ""; dstSubfolderSpec = 13; files = {array([embed_file])}; name = "Embed App Extensions"; runOnlyForDeploymentPostprocessing = 0;')
app_target = target("FollowerTracker", shared + app, list(resources.items()), product_app, extra_phases=[embed], dependencies=[dependency])
test_proxy = add("test-app-proxy", f'isa = PBXContainerItemProxy; containerPortal = {identifier("project")}; proxyType = 1; remoteGlobalIDString = {app_target}; remoteInfo = FollowerTracker;')
test_dependency = add("test-app-dependency", f'isa = PBXTargetDependency; target = {app_target}; targetProxy = {test_proxy};')
test_sources = phase("FollowerTrackerTests-sources", "PBXSourcesBuildPhase", [(path, refs[path]) for path in tests])
test_package = add("package-product:tests", f'isa = XCSwiftPackageProductDependency; package = {package}; productName = FollowerCore;')
test_link = add("link:tests", f'isa = PBXBuildFile; productRef = {test_package};')
test_frameworks = add("frameworks:tests", f'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = {array([test_link])}; runOnlyForDeploymentPostprocessing = 0;')
test_configuration = configurations("FollowerTrackerTests", {
    "PRODUCT_NAME": "$(TARGET_NAME)", "PRODUCT_BUNDLE_IDENTIFIER": "dev.datell.followertracker.tests",
    "GENERATE_INFOPLIST_FILE": "YES", "TEST_HOST": "$(BUILT_PRODUCTS_DIR)/FollowerTracker.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/FollowerTracker",
    "BUNDLE_LOADER": "$(TEST_HOST)", "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks @loader_path/Frameworks",
    "SKIP_INSTALL": "YES"})
test_target = add("target:tests", f'isa = PBXNativeTarget; buildConfigurationList = {test_configuration}; buildPhases = {array([test_sources, test_frameworks])}; buildRules = (); dependencies = {array([test_dependency])}; name = FollowerTrackerTests; packageProductDependencies = {array([test_package])}; productName = FollowerTrackerTests; productReference = {product_tests}; productType = "com.apple.product-type.bundle.unit-test";')
project_config = configurations("project", {})
project = add("project", f'isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 2660; }}; buildConfigurationList = {project_config}; compatibilityVersion = "Xcode 14.0"; developmentRegion = ko; hasScannedForEncodings = 0; knownRegions = (ko,en,Base,); mainGroup = {group}; packageReferences = {array([package])}; productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = {array([app_target, widget_target, test_target])};')
directory = ROOT / "FollowerTracker.xcodeproj"
directory.mkdir(exist_ok=True)
content = "// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n"
content += "\n".join(f"{key} = {{ {body} }};" for key, body in objects.items())
content += f"\n}}; rootObject = {project}; }}\n"
(directory / "project.pbxproj").write_text(content)
schemes = directory / "xcshareddata/xcschemes"
schemes.mkdir(parents=True, exist_ok=True)
reference = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{app_target}" BuildableName="FollowerTracker.app" BlueprintName="FollowerTracker" ReferencedContainer="container:followertracker.xcodeproj"/>'
reference = reference.replace("followertracker.xcodeproj", "FollowerTracker.xcodeproj")
test_reference = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{test_target}" BuildableName="FollowerTrackerTests.xctest" BlueprintName="FollowerTrackerTests" ReferencedContainer="container:FollowerTracker.xcodeproj"/>'
(schemes / "FollowerTracker.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2660" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{reference}</BuildActionEntry><BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="NO">{test_reference}</BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">{test_reference}</TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>''')
print("Generated", directory)
