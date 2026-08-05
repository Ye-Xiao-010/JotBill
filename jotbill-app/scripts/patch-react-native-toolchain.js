const fs = require('fs');
const path = require('path');

const pluginRoot = path.join(__dirname, '..', 'node_modules', '@react-native', 'gradle-plugin');
const files = [
  'react-native-gradle-plugin/build.gradle.kts',
  'settings-plugin/build.gradle.kts',
  'shared/build.gradle.kts',
  'shared-testutil/build.gradle.kts',
  'react-native-gradle-plugin/src/main/kotlin/com/facebook/react/utils/JdkConfiguratorUtils.kt',
];

for (const relativePath of files) {
  const filePath = path.join(pluginRoot, relativePath);
  if (!fs.existsSync(filePath)) {
    throw new Error(`React Native Gradle plugin file not found: ${relativePath}`);
  }

  const source = fs.readFileSync(filePath, 'utf8');
  const patched = source
    .replace(/jvmToolchain\(17\)/g, 'jvmToolchain(19)')
    .replace(/JavaVersion\.VERSION_17/g, 'JavaVersion.VERSION_19');
  if (patched !== source) {
    fs.writeFileSync(filePath, patched, 'utf8');
  }
}
