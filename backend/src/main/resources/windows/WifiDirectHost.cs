using System;
using System.Collections.Concurrent;
using Windows.Devices.WiFiDirect;
using Windows.Security.Credentials;

// Built against the WinRT metadata shipped with Windows.
public static class WifiDirectHost
{
    private static readonly ConcurrentDictionary<string, WiFiDirectDevice> clients =
        new ConcurrentDictionary<string, WiFiDirectDevice>();

    private static void Accept(WiFiDirectConnectionListener sender, WiFiDirectConnectionRequestedEventArgs args)
    {
        try
        {
            var request = args.GetConnectionRequest();
            try {
                var operation = WiFiDirectDevice.FromIdAsync(request.DeviceInformation.Id);
                operation.Completed = (result, status) => {
                    try {
                        var device = result.GetResults();
                        if (device == null) return;
                        if (!clients.TryAdd(device.DeviceId, device)) { device.Dispose(); return; }
                        device.ConnectionStatusChanged += Disconnected;
                        Disconnected(device, null);
                    } catch (Exception e) { Console.WriteLine("Peer connection failed: " + e.Message); }
                    finally { request.Dispose(); }
                };
            } catch { request.Dispose(); throw; }
        }
        catch (Exception e) { Console.WriteLine("Peer connection failed: " + e.Message); }
    }

    private static void Disconnected(WiFiDirectDevice device, object args)
    {
        WiFiDirectDevice held;
        if (device.ConnectionStatus == WiFiDirectConnectionStatus.Disconnected &&
            clients.TryRemove(device.DeviceId, out held))
        {
            held.ConnectionStatusChanged -= Disconnected;
            held.Dispose();
        }
    }

    public static int Main(string[] args)
    {
        try {
            if (args.Length == 1 && args[0] == "--probe") {
                Console.WriteLine(new WiFiDirectAdvertisementPublisher().Status);
                return 0;
            }
            Run();
            return 0;
        } catch (Exception e) {
            Console.WriteLine("Windows Wi-Fi Direct failed: " + e.Message);
            return 1;
        }
    }

    private static void Run()
    {
        var publisher = new WiFiDirectAdvertisementPublisher();
        var listener = new WiFiDirectConnectionListener();
        listener.ConnectionRequested += Accept;
        var advertisement = publisher.Advertisement;
        advertisement.IsAutonomousGroupOwnerEnabled = true;
        advertisement.LegacySettings.IsEnabled = true;
        advertisement.LegacySettings.Ssid = Console.ReadLine();
        advertisement.LegacySettings.Passphrase = new PasswordCredential { Password = Console.ReadLine() };
        publisher.StatusChanged += (sender, args) => {
            if (args.Status == WiFiDirectAdvertisementPublisherStatus.Started) Console.WriteLine("ACTIVE");
            if (args.Status == WiFiDirectAdvertisementPublisherStatus.Aborted)
            {
                Console.WriteLine("Windows could not maintain the Wi-Fi Direct group: " + args.Error);
                Environment.Exit(1);
            }
        };
        try {
            publisher.Start();
            Console.ReadLine(); // STOP or EOF when the Java owner closes or exits.
        } finally {
            publisher.Stop();
            listener.ConnectionRequested -= Accept;
            GC.KeepAlive(listener);
        }
    }
}
