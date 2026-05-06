
# BPMetrics

**BPMetrics** is a heart rate analysis platform consisting of a Wear OS recording app and an Android phone app for in-depth analysis. Originally designed to track physiological responses during gaming (like identifying high-intensity moments where my heart is thumping), it is equally useful for athletes, concert-goers, or even in therapeutic settings when aligning visual/audio data with a heart rate is desired.

## 🚀 Features

### ⌚ Wear OS Module
- **Real-time Monitoring:** View live heart rate during sessions.
- **Easy Recording:** Start and stop heart rate recordings directly from your wrist.
- **Seamless Sync:** Automatically transfer recordings to your phone for analysis.

### 📱 Mobile Module
- **Library Management:** Organize recordings with custom categories and tags.
- **Detailed Analysis:** Visualize heart rate data with BPM-over-time graphs.
- **Statistics:** View minimum, average, and maximum heart rate for individual sessions or aggregated by category/tag.
- **Flexible Export:** Export raw data (CSV) or visualizations (Images/Video) for sharing or further analysis.
- **Automatic Video Sync:** Overlapping heart rate and video data is automatically synced when exporting a video.
- **Local First:** All personal data remains on your device. No cloud storage, no privacy compromises.

## 🏗 Project Structure

The project is organized into three main modules:
- `:core`: Shared Kotlin-first data models and utilities used by both Wear and Mobile.
- `:wear`: The Wear OS application for heart rate capture and data syncing.
- `:mobile`: The Android phone application for data management, visualization, and export.

## 🛠 Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose (for Mobile) and Compose for Wear OS.
- **Local Database:** Room for efficient, local-only data persistence.
- **Data Sync:** Wearable Data Layer API for communication between watch and phone.
- **Exporting:** Media3 for video generation and custom drawing for image exports. CSV for record exports

## 📸 Screenshots
### Library, Tags, and Video Export Settings
<p align="center">
  <img alt="Library" src="https://github.com/user-attachments/assets/11e6fc03-1504-4745-bd76-e348b7b82ff6" width="33%" />
  <img alt="Tag Management" src="https://github.com/user-attachments/assets/9a097cb5-7d99-420f-b501-c9a0e438cdf4" width="33%" />
  <img alt="Video Export Settings" src="https://github.com/user-attachments/assets/dab73087-3fef-4530-97ef-882e60d36032" width="33%"/>
</p>

### Individual Record (Basic and Detailed)
<p align="center">
  <img alt="Record Basic" src="https://github.com/user-attachments/assets/58291add-1de2-4e45-b7ea-357389587094" width="33%" />
  <img alt="Record Detail" src="https://github.com/user-attachments/assets/f4018477-a113-4b80-aed3-66ce6071cace" width="33%" />
  <img alt="Record Detail Split" width="33%" src="https://github.com/user-attachments/assets/1533c05b-33db-4d17-a4b5-c8453d280280" />
</p>

### Exporting Image/Video
<p align="center">
  <img alt="Image Export" width="33%" src="https://github.com/user-attachments/assets/d95e6e59-cb3b-4d03-8d94-3c9d0aa9eca6" />  
  <img alt="Video Export" width="33%" src="https://github.com/user-attachments/assets/99aaef32-31cd-482d-abda-a4b2ac08b530" />
</p>

### Analysis of Records
<p align="center">
  <img alt="Max Rankings" width="33%" src="https://github.com/user-attachments/assets/e57fac13-f0b8-4b3b-bc95-403990210925" />
  <img alt="Average Rankings" width="33%" src="https://github.com/user-attachments/assets/987e3b43-df9d-43b0-99fe-0eca543e32d1" />
  <img alt="Min Rankings" width="33%" src="https://github.com/user-attachments/assets/542bffa7-c5fc-4b47-bda7-7f05db111a90" />
</p>

## Demo Video
*(Coming soon)*

## 📄 License
Copyright © 2025 Danny Inga. All rights reserved.
