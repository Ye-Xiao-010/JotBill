module.exports = {
  preset: 'react-native',
  moduleNameMapper: {
    '^react-native-config$': '<rootDir>/react-native-config.mock.js',
    '^@react-native-async-storage/async-storage$':
      '<rootDir>/node_modules/@react-native-async-storage/async-storage/jest/async-storage-mock',
    '^react-native-fs$': '<rootDir>/react-native-fs.mock.js',
    '^react-native-zip-archive$': '<rootDir>/react-native-zip-archive.mock.js',
    '^react-native-gifted-charts$': '<rootDir>/react-native-gifted-charts.mock.js',
    '^react-native-audio-recorder-player$':
      '<rootDir>/react-native-audio-recorder-player.mock.js',
  },
  transformIgnorePatterns: [
    'node_modules/(?!((@)?react-native|@react-navigation|@sentry|lucide-react-native|react-native-.*)/)',
  ],
};
