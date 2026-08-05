module.exports = {
  CachesDirectoryPath: '/tmp',
  DocumentDirectoryPath: '/tmp',
  downloadFile: jest.fn(() => ({
    jobId: 1,
    promise: Promise.resolve({ statusCode: 200, bytesWritten: 0 }),
  })),
  exists: jest.fn(async () => false),
  hash: jest.fn(async () => ''),
  mkdir: jest.fn(async () => undefined),
  stat: jest.fn(async () => ({ size: 0 })),
  unlink: jest.fn(async () => undefined),
  scanFile: jest.fn(),
  stopDownload: jest.fn(),
};
