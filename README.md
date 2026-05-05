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
*(Coming soon)*

## Demo Video
*(Coming soon)*

## 📄 License
Copyright © 2025 Danny Inga. All rights reserved.
