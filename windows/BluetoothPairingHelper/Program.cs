using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

// Usage: BluetoothPairingHelper [device name] [--address AA:BB:CC:DD:EE:FF] [--yes]
// The device is chosen by Bluetooth address when given. With a name only, the match must be
// unique and you confirm it, so a look-alike device nearby cannot be paired by accident.
string wantedName = "Galaxy A05s";
string? wantedAddress = null;
bool skipTargetPrompt = false;
for (int i = 0; i < args.Length; i++)
{
    if (args[i] == "--address" && i + 1 < args.Length) wantedAddress = args[++i];
    else if (args[i] == "--yes") skipTargetPrompt = true;
    else wantedName = args[i];
}

static string AddressOf(DeviceInformation device) => device.Id.Split('-').Last().ToUpperInvariant();

static bool Confirm(string question)
{
    Console.Write($"{question} [y/N] ");
    string? answer = Console.ReadLine();
    return answer is not null && answer.Trim().Equals("y", StringComparison.OrdinalIgnoreCase);
}

string selector = BluetoothDevice.GetDeviceSelectorFromPairingState(false);
DeviceInformationCollection devices = await DeviceInformation.FindAllAsync(selector);

List<DeviceInformation> matches = wantedAddress is not null
    ? devices.Where(d => AddressOf(d) == wantedAddress.ToUpperInvariant()).ToList()
    : devices.Where(d => string.Equals(d.Name, wantedName, StringComparison.OrdinalIgnoreCase)).ToList();

if (matches.Count == 0)
{
    Console.Error.WriteLine($"{wantedAddress ?? wantedName} was not found. Make the phone visible and retry.");
    return 2;
}

if (matches.Count > 1)
{
    Console.Error.WriteLine($"More than one device is named '{wantedName}'. Re-run with --address:");
    foreach (DeviceInformation match in matches)
        Console.Error.WriteLine($"  {match.Name}  {AddressOf(match)}");
    return 2;
}

DeviceInformation target = matches[0];
string display = $"{target.Name} ({AddressOf(target)})";

if (target.Pairing.IsPaired)
{
    Console.WriteLine($"{display} is already paired.");
    return 0;
}

if (!skipTargetPrompt && !Confirm($"Pair with {display}? Check the address matches your phone."))
{
    Console.Error.WriteLine("Cancelled.");
    return 3;
}

DeviceInformationCustomPairing pairing = target.Pairing.Custom;
pairing.PairingRequested += (_, request) =>
{
    var deferral = request.GetDeferral();
    try
    {
        switch (request.PairingKind)
        {
            case DevicePairingKinds.ConfirmPinMatch:
                Console.WriteLine($"The number on the phone must be: {request.Pin}");
                if (Confirm("Does it match?")) request.Accept();
                break;
            case DevicePairingKinds.ConfirmOnly:
                if (Confirm($"Accept pairing with {display}?")) request.Accept();
                break;
            case DevicePairingKinds.DisplayPin:
                Console.WriteLine($"Enter this PIN on the phone: {request.Pin}");
                request.Accept();
                break;
            case DevicePairingKinds.ProvidePin:
                Console.Write("Type the PIN shown on the phone: ");
                string pin = Console.ReadLine() ?? "";
                if (pin.Length > 0) request.Accept(pin);
                break;
        }
    }
    finally
    {
        deferral.Complete();
    }
};

DevicePairingKinds acceptedKinds =
    DevicePairingKinds.ConfirmOnly |
    DevicePairingKinds.DisplayPin |
    DevicePairingKinds.ProvidePin |
    DevicePairingKinds.ConfirmPinMatch;

DevicePairingResult result = await pairing.PairAsync(
    acceptedKinds, DevicePairingProtectionLevel.Default);
Console.WriteLine($"Pairing result: {result.Status}");
return result.Status is DevicePairingResultStatus.Paired or DevicePairingResultStatus.AlreadyPaired ? 0 : 1;
