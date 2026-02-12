# Faklify | Smart Media Engine

Faklify is a dynamic media player designed under the principle of **Zero-Configuration**. The project abstracts the complexity of managing external dependencies and network streams to offer a robust and autonomous playback solution.

## 🛠️ Technology Stack

* **Language**: Java 21
* **UI Framework**: JavaFX
* **Multimedia**: VLCj (LibVLC)
* **Data Handling**: Advanced concurrency (ThreadPools) and NIO.2 for file management.

## 🚀 Design Philosophy

1. **Autonomy**: The software is designed to self-manage the necessary binaries, eliminating the need for manual installation by the user.
2. **Resource Efficiency**: Implements a cache cleaning system based on time and disk space to maintain a light footprint on the system.
3. **Resilience**: Handles network errors and automatic state restoration after unexpected shutdowns.

## 💾 Installation & Setup

### 1. Ready-to-Use Installer (Recommended)
You can find the pre-compiled 64-bit Windows installer in the **[Releases](../../releases)** section of this repository. This version is fully packaged and requires no extra steps.

### 2. Manual Setup (From Source)
If you prefer to run or build the project yourself, follow these steps to prepare the environment:

1. **Clone the repository**:
   ```bash
   git clone https://github.com/n3v3rm1nd8/Faklify.git

2. **Download the Libraries**:
   Go to the **[Releases](../../releases)** section and download the lib.rar file. It is located at the same directory level as the .msi installer.

3. **Placement & Extraction**:

- Move the 'lib.rar' to: app\src\main\resources

- Extract its contents directly into that folder.

- You may delete the '.rar' file once the binaries (FFmpeg) are extracted.

## 🔨 Building a New Installer
If you have made changes to the code and want to generate a new .msi installer:

1. Ensure you have the JDK 21 and Wix Toolset configured.

2. Run the automation script:
   ```bash
   build_msi.bat
