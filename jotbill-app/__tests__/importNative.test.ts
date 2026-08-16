import { NativeModules } from 'react-native';
import { nativeImport } from '../src/native/import';

const modules = NativeModules as unknown as Record<string, unknown>;

describe('nativeImport', () => {
  it('透传密码与候选文件并解析原生 JSON', async () => {
    const inspection = {
      filePath: 'content://bill.zip',
      source: 'wechat',
      format: 'zip',
      fileHash: 'abc',
      encrypted: false,
      duplicateFile: false,
      needsCandidateSelection: false,
      candidates: [],
      sampleErrors: [],
    };
    const inspectImport = jest.fn().mockResolvedValue(JSON.stringify(inspection));
    modules.Backup = { inspectImport };

    await expect(nativeImport.inspectImport('', 'pwd', 'wechat.csv')).resolves.toEqual(inspection);
    expect(inspectImport).toHaveBeenCalledWith('', 'pwd', 'wechat.csv');
  });

  it('未知错误码按对应操作默认码归一化', async () => {
    const executeImport = jest.fn().mockRejectedValue({ code: 'UNEXPECTED', message: 'boom' });
    modules.Backup = { executeImport };

    await expect(nativeImport.executeImport('content://bill.zip')).rejects.toMatchObject({
      name: 'NativeBackupError',
      operation: 'executeImport',
      code: 'IMPORT_FAILED',
    });
  });
});
