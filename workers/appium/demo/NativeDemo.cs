using System;
using System.IO;
using System.Windows.Forms;

namespace Calendula597.Demo {
    public static class Program {
        [STAThread]
        public static void Main() {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            var form = new Form {
                Text = "原生 Windows 演示",
                Width = 480,
                Height = 220
            };
            var noteInput = new TextBox {
                Name = "noteInput",
                Left = 24,
                Top = 24,
                Width = 300
            };
            var save = new Button {
                Name = "saveButton",
                Text = "保存",
                Left = 340,
                Top = 20,
                Width = 90
            };
            var status = new Label {
                Name = "statusLabel",
                Text = "尚未保存",
                Left = 24,
                Top = 80,
                Width = 400
            };
            save.Click += (sender, args) => {
                var directory = Environment.GetEnvironmentVariable("NATIVE_DEMO_USER_DATA");
                if (string.IsNullOrEmpty(directory)) {
                    directory = Path.Combine(Path.GetTempPath(), "native-demo-default");
                }
                Directory.CreateDirectory(directory);
                File.WriteAllText(Path.Combine(directory, "note.txt"), noteInput.Text);
                status.Text = "已保存 " + noteInput.Text;
            };
            form.Controls.Add(noteInput);
            form.Controls.Add(save);
            form.Controls.Add(status);
            Application.Run(form);
        }
    }
}
