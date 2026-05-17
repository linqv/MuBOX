use comic_core::archive::{open_local_archive, ArchiveFormat};
use std::fs::File;
use std::io::{Cursor, Write};
use tempfile::{NamedTempFile, TempDir};

#[test]
fn tar_archive_sorts_images_and_extracts_requested_entry() {
    let archive = make_tar(&[
        ("10.jpg", b"ten".as_slice()),
        ("notes.txt", b"skip".as_slice()),
        ("nested/2.png", b"two".as_slice()),
        ("1.jpg", b"one".as_slice()),
    ]);

    let mut session = open_local_archive(archive.path(), ArchiveFormat::Tar).unwrap();

    assert_eq!(
        vec!["1.jpg", "nested/2.png", "10.jpg"],
        session.page_names()
    );
    assert_eq!(b"one".to_vec(), session.extract_page(0).unwrap());
    assert_eq!(b"two".to_vec(), session.extract_page(1).unwrap());
}

#[test]
fn seven_z_archive_sorts_images_and_extracts_requested_entry() {
    let archive = make_7z(&[
        ("10.jpg", b"ten".as_slice()),
        ("notes.txt", b"skip".as_slice()),
        ("nested/2.png", b"two".as_slice()),
        ("1.jpg", b"one".as_slice()),
    ]);

    let mut session = open_local_archive(archive.path(), ArchiveFormat::SevenZ).unwrap();

    assert_eq!(
        vec!["1.jpg", "nested/2.png", "10.jpg"],
        session.page_names()
    );
    assert_eq!(b"one".to_vec(), session.extract_page(0).unwrap());
    assert_eq!(b"two".to_vec(), session.extract_page(1).unwrap());
}

fn make_tar(entries: &[(&str, &[u8])]) -> NamedTempFile {
    let file = NamedTempFile::new().unwrap();
    {
        let mut writer = File::create(file.path()).unwrap();
        for (name, bytes) in entries {
            write_tar_header(&mut writer, name, bytes.len() as u64);
            writer.write_all(bytes).unwrap();
            let padding = padding_len(bytes.len() as u64);
            writer.write_all(&vec![0u8; padding]).unwrap();
        }
        writer.write_all(&[0u8; 1024]).unwrap();
    }
    file
}

fn write_tar_header(writer: &mut File, name: &str, size: u64) {
    let mut header = [0u8; 512];
    let name_bytes = name.as_bytes();
    header[..name_bytes.len()].copy_from_slice(name_bytes);
    write_octal(&mut header[100..108], 0o644);
    write_octal(&mut header[108..116], 0);
    write_octal(&mut header[116..124], 0);
    write_octal(&mut header[124..136], size);
    write_octal(&mut header[136..148], 0);
    header[148..156].fill(b' ');
    header[156] = b'0';
    header[257..263].copy_from_slice(b"ustar\0");
    header[263..265].copy_from_slice(b"00");
    let checksum: u32 = header.iter().map(|byte| *byte as u32).sum();
    write_checksum(&mut header[148..156], checksum);
    writer.write_all(&header).unwrap();
}

fn write_octal(target: &mut [u8], value: u64) {
    target.fill(0);
    let value = format!("{value:0width$o}", width = target.len() - 1);
    target[..value.len()].copy_from_slice(value.as_bytes());
}

fn write_checksum(target: &mut [u8], value: u32) {
    target.fill(0);
    let value = format!("{value:06o}\0 ");
    target[..value.len()].copy_from_slice(value.as_bytes());
}

fn padding_len(size: u64) -> usize {
    ((512 - (size % 512)) % 512) as usize
}

fn make_7z(entries: &[(&str, &[u8])]) -> NamedTempFile {
    let temp = TempDir::new().unwrap();
    let archive = NamedTempFile::new().unwrap();
    let mut writer = sevenz_rust2::ArchiveWriter::create(archive.path()).unwrap();
    for (name, bytes) in entries {
        let entry = sevenz_rust2::ArchiveEntry::new_file(name);
        writer
            .push_archive_entry(entry, Some(Cursor::new(bytes.to_vec())))
            .unwrap();
    }
    writer.finish().unwrap();
    drop(temp);
    archive
}
