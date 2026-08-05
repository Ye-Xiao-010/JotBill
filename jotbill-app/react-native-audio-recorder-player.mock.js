const AudioRecorderPlayer = {
  addRecordBackListener: jest.fn(),
  removeRecordBackListener: jest.fn(),
  startRecorder: jest.fn(async () => '/tmp/recording.m4a'),
  stopRecorder: jest.fn(async () => '/tmp/recording.m4a'),
};

module.exports = {
  __esModule: true,
  default: AudioRecorderPlayer,
  AudioEncoderAndroidType: { AAC: 'aac' },
  AudioSourceAndroidType: { MIC: 'mic' },
  OutputFormatAndroidType: { MPEG_4: 'mpeg_4' },
};
