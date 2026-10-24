# ApkForge
Android Studio mini versi APK: edit Java -> build -> sign -> install, semuanya di HP.

## Sebelum push
1. Taruh binary **aapt2 ARM64 (Android)** di `app/src/main/jniLibs/arm64-v8a/libaapt2.so`
   (harus build untuk Android/bionic yang berdiri sendiri, bukan binary Termux yang butuh lib $PREFIX).
2. Push ke GitHub (branch `main`). Workflow `Build APK` akan jalan otomatis.
3. Unduh artifact `ApkForge-debug` dari tab Actions, install di HP.

## Push dari Termux
    pkg install git
    cd ApkForge
    git init && git add . && git commit -m "init ApkForge"
    git branch -M main
    git remote add origin https://github.com/USERNAME/ApkForge.git
    git push -u origin main      # password = Personal Access Token

## Alur build di dalam aplikasi
aapt2 compile -> aapt2 link -> ECJ -> D8 -> gabung dex -> apksig -> install

## Fitur (v0.2)
- Banyak proyek: buat baru (nama + package), buka, hapus
- Banyak file per proyek: tambah file `.java` / `.xml`, ganti file, hapus file
- Syntax highlight untuk Java dan XML
- Build memakai semua file `src/**/*.java` dan seluruh folder `res/`

## Update dari zip baru
    cd ~ && unzip -o /sdcard/Download/ApkForge.zip
    cd ApkForge && git add . && git commit -m "v0.2"
    read -s -p "Token: " T; echo
    git push https://SenzaProject:$T@github.com/SenzaProject/ApkForge.git main
