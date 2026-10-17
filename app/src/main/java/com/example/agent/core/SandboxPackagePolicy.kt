package com.example.agent.core

/**
 * E2B Cloud MicroVM package policy.
 *
 * Declares packages whose default distribution exceeds the microVM memory/disk budget
 * (multi-GB wheels or CUDA stacks) and maps them to actionable alternatives.
 * Consulted by [CommandLinter] and [InstallPackageTool] before any pip invocation.
 */
object SandboxPackagePolicy {

  /**
   * Maps normalized package name -> actionable remediation guidance.
   */
  val HEAVY_PACKAGES: Map<String, String> = mapOf(
    "torch" to "PyTorch default wheels bundle multi-GB CUDA runtimes. Use the CPU-only build: python3 -m pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch",
    "torchvision" to "torchvision pulls PyTorch + CUDA runtimes. Use the CPU-only index: python3 -m pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torchvision",
    "torchaudio" to "torchaudio pulls PyTorch + CUDA runtimes. Use the CPU-only index: python3 -m pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torchaudio",
    "tensorflow" to "TensorFlow wheels exceed the microVM budget. Use a lighter alternative or a system build (sudo apt-get install -y python3-tensorflow) if available.",
    "tensorflow-cpu" to "TensorFlow wheels exceed the microVM budget. Use a lighter alternative such as scikit-learn.",
    "tensorflow-gpu" to "TensorFlow GPU wheels exceed the microVM budget. Use a lighter alternative such as scikit-learn.",
    "jax" to "JAX pulls a multi-GB jaxlib/cuda stack. Use numpy/scipy for numeric work instead.",
    "jaxlib" to "jaxlib wheels bundle large CUDA runtimes. Use numpy/scipy for numeric work instead.",
    "keras" to "Keras trains on TensorFlow/PyTorch backends (multi-GB). Use scikit-learn for classic ML.",
    "easyocr" to "EasyOCR pulls PyTorch + CUDA (>2 GB) and exhausts the microVM. For OCR use: sudo apt-get update && sudo apt-get install -y tesseract-ocr tesseract-ocr-eng && python3 -m pip install --no-cache-dir pytesseract pillow",
    "paddlepaddle" to "PaddlePaddle wheels exceed the microVM budget. For OCR use tesseract-ocr via apt instead.",
    "paddleocr" to "PaddleOCR pulls PaddlePaddle (>1 GB) and exhausts the microVM. For OCR use: sudo apt-get update && sudo apt-get install -y tesseract-ocr tesseract-ocr-eng && python3 -m pip install --no-cache-dir pytesseract pillow"
  )
}
