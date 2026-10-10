// Shows a preview of the selected image next to an image parameter input.
// Kept out of index.jelly so no inline script or job-controlled values end up in JavaScript.
Behaviour.specify(".jenkinsocr-image-input", "jenkinsocr-image-preview", 0, function (input) {
  input.addEventListener("change", function () {
    const preview = input.parentElement.querySelector(".jenkinsocr-image-preview");
    if (!preview) {
      return;
    }
    if (input.files && input.files[0]) {
      const reader = new FileReader();
      reader.onload = function (e) {
        preview.src = e.target.result;
        preview.style.display = "block";
      };
      reader.readAsDataURL(input.files[0]);
    } else {
      preview.src = "";
      preview.style.display = "none";
    }
  });
});
