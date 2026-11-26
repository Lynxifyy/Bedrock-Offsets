fn main() {
    for text in std::env::args().skip(1) {
        println!(
            "type({text}) => {:?}",
            offset_core::demangle::demangle_type(&text)
        );
    }
}
