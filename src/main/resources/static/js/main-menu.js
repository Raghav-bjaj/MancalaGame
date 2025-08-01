// src/main/resources/static/js/main-menu.js

document.addEventListener('DOMContentLoaded', () => {
    // Get the elements needed for the modal
    const modalOverlay = document.getElementById('rulesModalOverlay');
    const openModalBtn = document.getElementById('howToPlayBtn');
    const closeModalBtn = document.getElementById('closeModalBtn');

    // Function to open the modal
    const openModal = () => {
        if (modalOverlay) {
            modalOverlay.style.display = 'flex';
        }
    };

    // Function to close the modal
    const closeModal = () => {
        if (modalOverlay) {
            modalOverlay.style.display = 'none';
        }
    };

    // Event listener to open the modal when the "How to Play" button is clicked
    if (openModalBtn) {
        openModalBtn.addEventListener('click', openModal);
    }

    // Event listener to close the modal when the 'x' button is clicked
    if (closeModalBtn) {
        closeModalBtn.addEventListener('click', closeModal);
    }

    // Event listener to close the modal if the user clicks on the dark overlay
    if (modalOverlay) {
        modalOverlay.addEventListener('click', (event) => {
            // Only close if the click is on the overlay itself, not the content inside
            if (event.target === modalOverlay) {
                closeModal();
            }
        });
    }
});