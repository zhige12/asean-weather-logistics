using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

// Double-click installer for the offline demo package.
//
// Layout of the shipped file:  [this exe] [payload zip] [8-byte zip length] [8-byte magic]
// The zip is byte-identical to asean-demo-offline.zip and contains one top folder,
// "asean-demo", so it is expanded straight into the directory the exe lives in.
//
// Why not a 7-Zip SFX: this machine only has 7z.sfx / 7zCon.sfx, and both were measured to
// IGNORE the whole config block (Path= and RunProgram= did nothing, files just land flat).
// The setup module that honours them, 7zS.sfx, is no longer shipped with 7-Zip. So the
// launcher is compiled here with the .NET Framework compiler that every Windows has.
//
// Compiled with csc from .NET Framework 4.x, which means C# 5 only: no string
// interpolation, no null-conditional operator, no nameof.

namespace AseanSetup
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainFrame());
        }
    }

    internal sealed class MainFrame : Form
    {
        private const string Magic = "ASEANPK1";
        private const string FolderName = "asean-demo";

        private readonly Label _status;
        private volatile string _fail;

        public MainFrame()
        {
            Text = "面向东盟跨境物流的突发气象预警与多路线协同调度平台 · 演示包";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = true;
            ClientSize = new Size(560, 140);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei", 9F);

            _status = new Label();
            _status.Dock = DockStyle.Fill;
            _status.Padding = new Padding(18, 12, 18, 12);
            _status.TextAlign = ContentAlignment.MiddleLeft;
            _status.Text = "正在准备…";
            Controls.Add(_status);

            Load += OnLoad;
        }

        private void OnLoad(object sender, EventArgs e)
        {
            var t = new Thread(Work);
            t.IsBackground = true;
            t.Start();
        }

        private void Say(string text)
        {
            try { BeginInvoke((Action)delegate { _status.Text = text; }); } catch { }
        }

        private void Work()
        {
            try
            {
                Run();
            }
            catch (Exception ex)
            {
                _fail = ex.Message;
            }

            try
            {
                BeginInvoke((Action)delegate
                {
                    if (_fail != null)
                    {
                        MessageBox.Show(this, _fail, "演示包启动失败",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                    Close();
                });
            }
            catch { }
        }

        private void Run()
        {
            string exe = Application.ExecutablePath;
            string dir = Path.GetDirectoryName(exe);

            // The single most common way this breaks: the judge double-clicks the exe while
            // still inside an archive preview, so Windows has copied it into a temp folder
            // that is deleted again on exit. Everything would then be unpacked into thin air.
            //
            // But "the exe sits under %TEMP%" is NOT by itself evidence of that: plenty of
            // machines (and every CI/agent session) relocate TEMP to another drive, and a
            // folder there is as stable as any other - refusing then blocks a perfectly good
            // demo with "please copy the file to the Desktop". The archive-preview copy lands
            // on the scratch folder of the SYSTEM drive, so the TEMP test only fires in that
            // case; the archive-tool markers (RAR$, $TEMP) stay unconditional.
            string probe = dir.ToLowerInvariant();
            string sysTemp = Path.GetTempPath().TrimEnd('\\').ToLowerInvariant();
            if ((IsSystemDriveScratch(sysTemp) &&
                 (probe == sysTemp || probe.StartsWith(sysTemp + "\\"))) ||
                probe.Contains("rar$") || probe.Contains("$temp"))
            {
                throw new Exception(
                    "检测到本程序正在压缩软件的预览窗口里运行，文件会在关闭后被连带删除。\n\n" +
                    "请先把 " + Path.GetFileName(exe) + " 复制到桌面，再双击它。");
            }

            string target = Path.Combine(dir, FolderName);
            string bat = Path.Combine(target, "start.bat");
            string idFile = Path.Combine(dir, FolderName + ".setup.id");

            long zipLen;
            long zipStart;
            ReadFooter(exe, out zipLen, out zipStart);

            string stamp = zipLen.ToString();
            bool already = File.Exists(bat) && File.Exists(idFile) &&
                           File.ReadAllText(idFile).Trim() == stamp;

            if (!already)
            {
                string staged = StageZip(exe, dir, target, zipStart, zipLen);

                Say("正在展开演示文件…（约 375MB，请稍候 10~30 秒）");
                RunTar(staged, dir);
                try { File.Delete(staged); } catch { }

                File.WriteAllText(idFile, stamp);
            }
            else
            {
                Say("演示文件已就绪，正在启动…");
            }

            if (!File.Exists(bat))
            {
                throw new Exception("解压完成后没有找到 " + FolderName + "\\start.bat，" +
                    "可能被杀毒软件拦截了一部分文件。请关闭实时防护后重试，或改用压缩包版。");
            }

            var psi = new ProcessStartInfo(bat);
            psi.WorkingDirectory = target;
            psi.UseShellExecute = true;
            Process.Start(psi);

            Thread.Sleep(400);
        }

        /// <summary>
        /// Is the scratch folder the one Windows itself picked on the system drive? That is the
        /// folder an archive preview copies into. A relocated TEMP (e.g. D:\Temp) is a folder the
        /// owner chose, so extracting next to it is exactly what was asked for.
        /// </summary>
        private static bool IsSystemDriveScratch(string tempLower)
        {
            string root = Path.GetPathRoot(tempLower);
            if (string.IsNullOrEmpty(root))
            {
                return false;
            }
            string systemDrive = Environment.GetEnvironmentVariable("SystemDrive") ?? "C:";
            string a = root.TrimEnd('\\', ':').ToUpperInvariant();
            string b = systemDrive.TrimEnd('\\', ':').ToUpperInvariant();
            return a.Length == 1 && a == b;
        }

        private void ReadFooter(string exe, out long zipLen, out long zipStart)
        {
            var fi = new FileInfo(exe);
            long total = fi.Length;
            if (total < 16)
            {
                throw new Exception("本文件不完整（只有 " + total + " 字节），" +
                    "演示文件没有附加在它后面，请重新获取完整的 asean-demo-setup.exe。");
            }

            var tail = new byte[16];
            using (var fs = new FileStream(exe, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            {
                fs.Seek(total - 16, SeekOrigin.Begin);
                if (fs.Read(tail, 0, 16) != 16)
                {
                    throw new Exception("读取文件尾部失败。");
                }
            }

            string magic = Encoding.ASCII.GetString(tail, 8, 8);
            if (magic != Magic)
            {
                throw new Exception("本文件尾部缺少演示数据（标记为 \"" + magic + "\"），" +
                    "说明传输不完整或被安全软件截断，请重新获取完整的 asean-demo-setup.exe。");
            }

            zipLen = BitConverter.ToInt64(tail, 0);
            zipStart = total - 16 - zipLen;
            if (zipLen <= 0 || zipStart < 0)
            {
                throw new Exception("演示数据长度异常（" + zipLen + " 字节），文件已损坏。");
            }
        }

        private string StageZip(string exe, string dir, string target, long zipStart, long zipLen)
        {
            // bsdtar cannot read a zip that has an exe glued in front of it, so the payload
            // is copied out first. Prefer the folder the exe lives in: the system drive is
            // often the small one, and the extracted package lands there anyway.
            string name = FolderName + ".payload.tmp.zip";
            string staged = Path.Combine(dir, name);
            if (!HasRoom(dir, zipLen))
            {
                staged = Path.Combine(Path.GetTempPath(), name);
                if (!HasRoom(Path.GetTempPath(), zipLen))
                {
                    throw new Exception("磁盘剩余空间不足（需要约 " +
                        (zipLen / 1024 / 1024) + "MB 临时空间）。请清理后重试。");
                }
            }

            const int chunk = 1 << 20;
            var buf = new byte[chunk];
            long done = 0;
            int lastPct = -1;

            using (var src = new FileStream(exe, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            using (var dst = new FileStream(staged, FileMode.Create, FileAccess.Write))
            {
                src.Seek(zipStart, SeekOrigin.Begin);
                while (done < zipLen)
                {
                    int want = (int)Math.Min(chunk, zipLen - done);
                    int got = src.Read(buf, 0, want);
                    if (got <= 0)
                    {
                        throw new Exception("读取内嵌数据时提前结束，文件已损坏。");
                    }
                    dst.Write(buf, 0, got);
                    done += got;

                    int pct = (int)(done * 100 / zipLen);
                    if (pct != lastPct)
                    {
                        lastPct = pct;
                        Say("正在释放演示文件 " + pct + "%（共 2 步的第 1 步）");
                    }
                }
            }
            return staged;
        }

        private static bool HasRoom(string path, long need)
        {
            try
            {
                string root = Path.GetPathRoot(path);
                if (string.IsNullOrEmpty(root))
                {
                    return false;
                }
                var di = new DriveInfo(root);
                return di.IsReady && di.AvailableFreeSpace > need + 128L * 1024 * 1024;
            }
            catch
            {
                return false;
            }
        }

        private void RunTar(string zip, string outDir)
        {
            string tar = Path.Combine(Environment.SystemDirectory, "tar.exe");
            if (!File.Exists(tar))
            {
                throw new Exception("系统里没有 tar.exe（Windows 10 1809 及以上自带）。" +
                    "请改用压缩包版 asean-demo-offline.zip。");
            }

            var psi = new ProcessStartInfo(tar, "-xf \"" + zip + "\" -C \"" + outDir + "\"");
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardError = true;
            psi.WorkingDirectory = outDir;

            var err = new StringBuilder();
            using (var p = new Process())
            {
                p.StartInfo = psi;
                p.ErrorDataReceived += delegate(object s, DataReceivedEventArgs e2)
                {
                    if (e2.Data != null)
                    {
                        err.AppendLine(e2.Data);
                    }
                };
                p.Start();
                p.BeginErrorReadLine();
                if (!p.WaitForExit(10 * 60 * 1000))
                {
                    try { p.Kill(); } catch { }
                    throw new Exception("解压超时（10 分钟）。");
                }
                if (p.ExitCode != 0)
                {
                    string text = err.ToString();
                    if (text.Length > 600)
                    {
                        text = text.Substring(0, 600);
                    }
                    throw new Exception("解压失败，tar 退出码 " + p.ExitCode + "。\n" + text);
                }
            }
        }
    }
}
