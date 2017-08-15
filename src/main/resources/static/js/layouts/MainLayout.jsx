import React, {Component} from 'react';
import { Modal, Button } from 'react-bootstrap';
import TopNavBar from '../components/TopNavBar';
import SideNavBar from '../components/SideNavBar';
import FileTree from '../components/FileTree';
import Footer from '../components/Footer';


class MainLayout extends Component {

    constructor(props) {
        super(props);
        this.state = {
          isOpen: false,
          modalData: {}
        };


        this.openModal = this.openModal.bind(this);
        this.setModalData = this.setModalData.bind(this);
        this.hideModal = this.hideModal.bind(this);
    }

    openModal() {
        this.setState({
            isOpen: true
        });
    }

    setModalData(modalData) {
        this.setState({ modalData });
    }

    hideModal() {
        this.setState({
            isOpen: false
        })
    }

    render() {
        return (
            <div>

                <TopNavBar hasSideNavBar/>

                <div className="container-fluid">
                    <div className="row">

                        <SideNavBar
                            openModal={this.openModal}
                            setModalData={this.setModalData}/>

                        <div className="col-sm-8 col-md-offset-2 main">

                            { /* Main content will be placed here */ }
                            { this.props.children }

                            <Footer />

                        </div>

                        <FileTree
                            openModal={this.openModal}
                            setModalData={this.setModalData}/>

                    </div>
                </div>

                { /* Modal component to contain CSV viewer for uploaded files */ }
                <Modal
                    show={this.state.isOpen}
                    onHide={this.hideModal}
                    dialogClassName={this.state.modalData.className}>

                    <Modal.Header closeButton>
                        <Modal.Title>{this.state.modalData.title}</Modal.Title>
                    </Modal.Header>

                    <Modal.Body>
                        { this.state.modalData.content }
                    </Modal.Body>

                    <Modal.Footer>
                        <Button onClick={this.hideModal}>Close</Button>
                    </Modal.Footer>

                </Modal>

            </div>
        )
    }

}



export default MainLayout;